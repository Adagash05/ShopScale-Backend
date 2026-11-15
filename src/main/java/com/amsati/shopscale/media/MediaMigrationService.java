package com.amsati.shopscale.media;

import com.amsati.shopscale.media.MediaKeys.Kind;
import com.amsati.shopscale.media.s3.R2Client;
import com.amsati.shopscale.media.s3.SigV4;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Moves images that are stored in PostgreSQL to R2 in small, restartable batches. It is driven by a Super Admin from the Storage page.
 * Rules: an image is marked migrated only after the stored object's size and SHA-256 match the database bytes; database bytes are never deleted here;
 * every failure is recorded on the row (status FAILED, attempts, last_error) and retried by the next batch until {@link #MAX_ATTEMPTS}; progress is
 * in the table, so a restart or a crash resumes where it stopped. Runs one batch at a time per server process.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaMigrationService {
    public static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate jdbc;
    private final MediaService media;
    private final AtomicBoolean running = new AtomicBoolean();

    public record BatchResult(int found, int migrated, int failed, int remainingPending, int remainingFailed, String note) {}
    public record VerifyResult(int checked, int ok, int bad) {}
    public record Orphans(List<String> keys, long bytes, boolean truncated, int referencedKeys) {}

    /** Registers every image still stored only in the database as PENDING. Safe to run repeatedly. Returns how many rows were added. */
    public int discover() {
        int n = 0;
        n += jdbc.update("""
            INSERT INTO media_objects (id, business_id, kind, owner_id, storage, status, source)
            SELECT gen_random_uuid(), business_id, 'PRODUCT_IMAGE', id, 'DB', 'PENDING', 'MIGRATION' FROM product_images WHERE data IS NOT NULL
            ON CONFLICT (kind, owner_id) DO NOTHING""");
        n += jdbc.update("""
            INSERT INTO media_objects (id, business_id, kind, owner_id, storage, status, source)
            SELECT gen_random_uuid(), business_id, 'STORE_LOGO', business_id, 'DB', 'PENDING', 'MIGRATION' FROM store_logos WHERE data IS NOT NULL
            ON CONFLICT (kind, owner_id) DO NOTHING""");
        n += jdbc.update("""
            INSERT INTO media_objects (id, business_id, kind, owner_id, storage, status, source)
            SELECT gen_random_uuid(), business_id, 'INVOICE_LOGO', business_id, 'DB', 'PENDING', 'MIGRATION' FROM invoice_settings WHERE logo IS NOT NULL AND length(logo) > 0
            ON CONFLICT (kind, owner_id) DO NOTHING""");
        return n;
    }

    /** Migrates up to {@code limit} (1..100) images. Does nothing unless R2 is configured and not switched off. */
    public BatchResult runBatch(int limit) {
        if (!media.r2Active()) return new BatchResult(0, 0, 0, 0, 0, "R2 is not active (not configured, or MEDIA_STORAGE_MODE=DB). Nothing was changed.");
        if (!running.compareAndSet(false, true)) return new BatchResult(0, 0, 0, 0, 0, "A batch is already running.");
        try {
            discover();
            int max = Math.min(Math.max(limit, 1), 100);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, business_id, kind, owner_id FROM media_objects
                WHERE storage = 'DB' AND (status = 'PENDING' OR (status = 'FAILED' AND attempts < ?)) ORDER BY status, created_at LIMIT ?""", MAX_ATTEMPTS, max);
            int ok = 0, bad = 0;
            for (Map<String, Object> r : rows) {
                if (migrateOne((UUID) r.get("id"), (UUID) r.get("business_id"), Kind.valueOf((String) r.get("kind")), (UUID) r.get("owner_id"))) ok++; else bad++;
            }
            return new BatchResult(rows.size(), ok, bad, count("PENDING", "DB"), jdbc.queryForObject("SELECT count(*) FROM media_objects WHERE status = 'FAILED' AND storage = 'DB'", Integer.class), null);
        } finally { running.set(false); }
    }

    private int count(String status, String storage) {
        return jdbc.queryForObject("SELECT count(*) FROM media_objects WHERE status = ? AND storage = ?", Integer.class, status, storage);
    }

    private boolean migrateOne(UUID rowId, UUID businessId, Kind kind, UUID ownerId) {
        List<String> put = new ArrayList<>();
        try {
            byte[] bytes = legacyBytes(kind, ownerId);
            if (bytes == null || bytes.length == 0) throw new IllegalStateException("no image bytes in the database for this item");
            String type = ImageProcessor.sniff(bytes);
            if (type == null) throw new IllegalStateException("the stored bytes are not a JPEG, PNG or WebP image");
            String sha = SigV4.sha256Hex(bytes);
            UUID productId = kind == Kind.PRODUCT_IMAGE ? jdbc.queryForObject("SELECT product_id FROM product_images WHERE id = ?", UUID.class, ownerId) : null;
            String key = kind == Kind.PRODUCT_IMAGE ? MediaKeys.product(businessId, productId, ownerId, type) : MediaKeys.logo(businessId, kind, sha, type);
            if (!MediaKeys.belongsTo(businessId, key)) throw new IllegalStateException("computed key is outside the business folder");
            R2Client c = media.r2();
            c.put(key, bytes, type); put.add(key);
            var head = c.head(key).orElseThrow(() -> new IllegalStateException("object missing right after upload"));
            if (head.size() != bytes.length) throw new IllegalStateException("size mismatch after upload (" + head.size() + " vs " + bytes.length + ")");
            byte[] back = c.get(key).orElseThrow(() -> new IllegalStateException("object unreadable right after upload"));
            if (!SigV4.sha256Hex(back).equals(sha)) throw new IllegalStateException("SHA-256 mismatch after upload");
            String thumb = null, standard = null;
            if (kind == Kind.PRODUCT_IMAGE) {
                for (var v : ImageProcessor.variants(bytes, ImageProcessor.inspect(bytes))) {
                    String vk = MediaKeys.variant(key, v.name().equals("thumb") ? ImageProcessor.THUMB : ImageProcessor.STANDARD);
                    try { c.put(vk, v.data(), v.contentType()); put.add(vk); if (v.name().equals("thumb")) thumb = vk; else standard = vk; } catch (RuntimeException e) { log.warn("size copy skipped: {}", e.getMessage()); }
                }
            }
            jdbc.update("""
                UPDATE media_objects SET storage = 'R2', object_key = ?, thumb_key = ?, standard_key = ?, content_type = ?, size_bytes = ?, sha256 = ?, status = 'VERIFIED',
                  verified_at = now(), last_error = NULL, attempts = attempts + 1, updated_at = now() WHERE id = ?""", key, thumb, standard, type, bytes.length, sha, rowId);
            media.event(businessId, kind, "MIGRATED", null);
            return true;
        } catch (RuntimeException e) {
            for (String k : put) { try { media.r2().delete(k); } catch (RuntimeException ignored) { /* listed later as an orphan if it lingers */ } }
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jdbc.update("UPDATE media_objects SET status = 'FAILED', attempts = attempts + 1, last_error = ?, updated_at = now() WHERE id = ?", msg.substring(0, Math.min(msg.length(), 500)), rowId);
            media.event(businessId, kind, "MIGRATE_FAILED", msg);
            return false;
        }
    }

    private byte[] legacyBytes(Kind kind, UUID ownerId) {
        String sql = switch (kind) {
            case PRODUCT_IMAGE -> "SELECT data FROM product_images WHERE id = ?";
            case STORE_LOGO -> "SELECT data FROM store_logos WHERE business_id = ?";
            case INVOICE_LOGO -> "SELECT logo FROM invoice_settings WHERE business_id = ?";
        };
        List<byte[]> r = jdbc.query(sql, (rs, i) -> rs.getBytes(1), ownerId);   // not a stream: a NULL column must give null, not an NPE
        return r.isEmpty() ? null : r.get(0);
    }

    /** Puts every FAILED row back to PENDING so the next batch retries it (attempt counter reset). */
    public int retryFailed() {
        return jdbc.update("UPDATE media_objects SET status = 'PENDING', attempts = 0, updated_at = now() WHERE status = 'FAILED' AND storage = 'DB'");
    }

    /** Re-checks migrated objects (existence and size; with {@code deep} also SHA-256). An object that is missing or wrong is marked FAILED so serving uses the database copy. */
    public VerifyResult verify(int limit, boolean deep) {
        if (!media.properties().configured()) return new VerifyResult(0, 0, 0);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, business_id, kind, object_key, size_bytes, sha256 FROM media_objects WHERE storage = 'R2' AND status = 'VERIFIED'
            ORDER BY verified_at NULLS FIRST LIMIT ?""", Math.min(Math.max(limit, 1), 500));
        int ok = 0, bad = 0;
        for (Map<String, Object> r : rows) {
            String err = null;
            try {
                var head = media.r2().head((String) r.get("object_key"));
                if (head.isEmpty()) err = "object missing in R2";
                else if (head.get().size() != ((Number) r.get("size_bytes")).longValue()) err = "size differs from the recorded size";
                else if (deep) {
                    byte[] b = media.r2().get((String) r.get("object_key")).orElse(null);
                    if (b == null || !SigV4.sha256Hex(b).equals(((String) r.get("sha256")).trim())) err = "SHA-256 differs from the recorded value";
                }
            } catch (RuntimeException e) { err = "could not check: " + e.getMessage(); }
            if (err == null) { ok++; jdbc.update("UPDATE media_objects SET verified_at = now() WHERE id = ?", r.get("id")); }
            else {
                bad++;
                jdbc.update("UPDATE media_objects SET status = 'FAILED', last_error = ?, updated_at = now() WHERE id = ?", err, r.get("id"));
                media.event((UUID) r.get("business_id"), Kind.valueOf((String) r.get("kind")), "VERIFY_FAILED", err);
            }
        }
        return new VerifyResult(rows.size(), ok, bad);
    }

    /**
     * Rollback to database storage for up to {@code limit} images: R2-only images (uploaded after R2 was switched on) get their bytes copied back
     * after a SHA-256 check; images that still have their database bytes are simply switched back. R2 objects are NOT deleted. Set MEDIA_STORAGE_MODE=DB
     * first, otherwise the next migration batch would move them again.
     */
    public BatchResult rollback(int limit) {
        if (!running.compareAndSet(false, true)) return new BatchResult(0, 0, 0, 0, 0, "A batch is already running.");
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, business_id, kind, owner_id, object_key, sha256 FROM media_objects WHERE storage = 'R2' ORDER BY updated_at LIMIT ?", Math.min(Math.max(limit, 1), 100));
            int ok = 0, bad = 0;
            for (Map<String, Object> r : rows) {
                UUID biz = (UUID) r.get("business_id"), owner = (UUID) r.get("owner_id");
                Kind kind = Kind.valueOf((String) r.get("kind"));
                try {
                    byte[] have = legacyBytes(kind, owner);
                    if (have == null || have.length == 0) {
                        byte[] b = media.r2().get((String) r.get("object_key")).orElseThrow(() -> new IllegalStateException("object missing in R2 and no database copy"));
                        if (r.get("sha256") != null && !SigV4.sha256Hex(b).equals(((String) r.get("sha256")).trim())) throw new IllegalStateException("downloaded bytes do not match the recorded SHA-256");
                        restore(kind, biz, owner, b);
                    }
                    jdbc.update("UPDATE media_objects SET storage = 'DB', status = 'PENDING', attempts = 0, last_error = NULL, updated_at = now() WHERE id = ?", r.get("id"));
                    media.event(biz, kind, "RESTORED", null);
                    ok++;
                } catch (RuntimeException e) {
                    bad++;
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    jdbc.update("UPDATE media_objects SET last_error = ?, updated_at = now() WHERE id = ?", msg.substring(0, Math.min(msg.length(), 500)), r.get("id"));
                }
            }
            return new BatchResult(rows.size(), ok, bad, 0, 0, bad > 0 ? "Some images could not be restored; they remain in R2 and were not changed." : null);
        } finally { running.set(false); }
    }

    private void restore(Kind kind, UUID businessId, UUID owner, byte[] b) {
        switch (kind) {
            case PRODUCT_IMAGE -> jdbc.update("UPDATE product_images SET data = ?, size_bytes = ? WHERE id = ?", b, b.length, owner);
            case STORE_LOGO -> jdbc.update("INSERT INTO store_logos (business_id, data) VALUES (?, ?) ON CONFLICT (business_id) DO UPDATE SET data = EXCLUDED.data", businessId, b);
            case INVOICE_LOGO -> jdbc.update("UPDATE invoice_settings SET logo = ? WHERE business_id = ?", b, businessId);
        }
    }

    /** Objects under merchants/ that no media_objects row refers to (read-only; lists up to {@code max} keys). */
    public Orphans findOrphans(int max) {
        Set<String> known = new HashSet<>();
        jdbc.query("SELECT object_key, thumb_key, standard_key FROM media_objects WHERE object_key IS NOT NULL", rs -> { for (int i = 1; i <= 3; i++) { String k = rs.getString(i); if (k != null) known.add(k); } });
        List<String> orphans = new ArrayList<>();
        long bytes = 0; String token = null; boolean truncated = false;
        int pages = 0;
        do {
            R2Client.Listed p = media.r2().list("merchants/", token, 1000);
            for (int i = 0; i < p.keys().size(); i++) if (!known.contains(p.keys().get(i))) { if (orphans.size() < max) orphans.add(p.keys().get(i)); else truncated = true; bytes += p.sizes().get(i); }
            token = p.nextToken();
            if (++pages >= 200 && token != null) { truncated = true; break; }
        } while (token != null);
        return new Orphans(orphans, bytes, truncated, known.size());
    }

    /** Deletes exactly the listed orphan keys, after re-checking each is under merchants/ and referenced by no row. Returns how many were deleted. */
    public int deleteOrphans(List<String> keys) {
        int n = 0;
        for (String k : keys) {
            if (k == null || !k.startsWith("merchants/") || k.contains("..")) continue;
            Integer refs = jdbc.queryForObject("SELECT count(*) FROM media_objects WHERE object_key = ? OR thumb_key = ? OR standard_key = ?", Integer.class, k, k, k);
            if (refs != null && refs > 0) continue;
            media.r2().delete(k);
            n++;
        }
        return n;
    }

    public boolean isRunning() { return running.get(); }
}
