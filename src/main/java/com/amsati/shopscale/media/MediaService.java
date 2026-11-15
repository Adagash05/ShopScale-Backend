package com.amsati.shopscale.media;

import com.amsati.shopscale.media.MediaKeys.Kind;
import com.amsati.shopscale.media.s3.R2Client;
import com.amsati.shopscale.media.s3.SigV4;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The one place that talks to object storage. Image bytes live in Cloudflare R2 once it is configured; the database keeps only the reference
 * (media_objects). Old bytes in PostgreSQL are never deleted by this code, so serving can always fall back to them.
 * If R2 fails during an upload, the upload falls back to the database so a merchant is never blocked by a storage outage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaService {
    private final JdbcTemplate jdbc;
    private final MediaProperties props;
    private volatile R2Client client;

    public record Ref(UUID businessId, String objectKey, String thumbKey, String standardKey, String contentType, Long sizeBytes) {}

    public boolean r2Active() { return props.r2Allowed(); }
    public MediaProperties properties() { return props; }

    R2Client r2() {
        R2Client c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) client = new R2Client(props.endpoint(), props.getBucketName().trim(), props.getAccessKeyId().trim(), props.getSecretAccessKey().trim());
                c = client;
            }
        }
        return c;
    }

    /**
     * Puts a new image in R2 (and, for product pictures, smaller sizes), checks the stored size, records it as VERIFIED and returns true.
     * Returns false (nothing recorded, partial objects removed) when R2 is not active or anything failed; the caller then keeps the bytes in the database.
     * {@code productId} is only used for product images. Must be called with a business id taken from the server-side auth context.
     */
    public boolean storeNew(UUID businessId, Kind kind, UUID ownerId, UUID productId, byte[] bytes, String contentType) {
        if (!r2Active()) return false;
        List<String> put = new ArrayList<>();
        try {
            String sha = SigV4.sha256Hex(bytes);
            String key = kind == Kind.PRODUCT_IMAGE ? MediaKeys.product(businessId, productId, ownerId, contentType) : MediaKeys.logo(businessId, kind, sha, contentType);
            if (!MediaKeys.belongsTo(businessId, key)) throw new IllegalStateException("key outside tenant folder");
            Optional<Ref> previous = ref(kind, ownerId);
            r2().put(key, bytes, contentType); put.add(key);
            var head = r2().head(key).orElseThrow(() -> new IllegalStateException("object not found after upload"));
            if (head.size() != bytes.length) throw new IllegalStateException("stored size differs from uploaded size");
            String thumb = null, standard = null;
            if (kind == Kind.PRODUCT_IMAGE) {
                var info = ImageProcessor.inspect(bytes);
                for (var v : ImageProcessor.variants(bytes, info)) {
                    String vk = MediaKeys.variant(key, v.name().equals("thumb") ? ImageProcessor.THUMB : ImageProcessor.STANDARD);
                    try { r2().put(vk, v.data(), v.contentType()); put.add(vk); if (v.name().equals("thumb")) thumb = vk; else standard = vk; }
                    catch (RuntimeException e) { log.warn("Could not store the {} size of an image: {}", v.name(), e.getMessage()); }
                }
            }
            jdbc.update("""
                INSERT INTO media_objects (id, business_id, kind, owner_id, storage, object_key, thumb_key, standard_key, content_type, size_bytes, sha256, status, verified_at, source)
                VALUES (?, ?, ?, ?, 'R2', ?, ?, ?, ?, ?, ?, 'VERIFIED', now(), 'UPLOAD')
                ON CONFLICT (kind, owner_id) DO UPDATE SET storage = 'R2', object_key = EXCLUDED.object_key, thumb_key = EXCLUDED.thumb_key, standard_key = EXCLUDED.standard_key,
                  content_type = EXCLUDED.content_type, size_bytes = EXCLUDED.size_bytes, sha256 = EXCLUDED.sha256, status = 'VERIFIED', verified_at = now(),
                  attempts = 0, last_error = NULL, source = 'UPLOAD', updated_at = now()""",
                UUID.randomUUID(), businessId, kind.name(), ownerId, key, thumb, standard, contentType, bytes.length, sha);
            final String nk = key, nt = thumb, ns = standard;
            previous.ifPresent(p -> afterCommit(() -> deleteObjects(p, nk, nt, ns)));
            event(businessId, kind, "UPLOAD_OK", null);
            return true;
        } catch (RuntimeException e) {
            for (String k : put) { try { r2().delete(k); } catch (RuntimeException ignored) { /* an orphan; the Storage page will list it */ } }
            event(businessId, kind, "UPLOAD_FAILED", e.getMessage());
            log.warn("R2 upload failed, keeping the image in the database: {}", e.getMessage());
            return false;
        }
    }

    /** The verified R2 reference for an image, if there is one. FAILED or PENDING rows are ignored so serving falls back to the database bytes. */
    public Optional<Ref> verifiedRef(Kind kind, UUID ownerId) {
        return query("SELECT business_id, object_key, thumb_key, standard_key, content_type, size_bytes FROM media_objects WHERE kind = ? AND owner_id = ? AND storage = 'R2' AND status = 'VERIFIED'", kind, ownerId);
    }

    private Optional<Ref> ref(Kind kind, UUID ownerId) {
        return query("SELECT business_id, object_key, thumb_key, standard_key, content_type, size_bytes FROM media_objects WHERE kind = ? AND owner_id = ? AND object_key IS NOT NULL", kind, ownerId);
    }

    private Optional<Ref> query(String sql, Kind kind, UUID ownerId) {
        return jdbc.query(sql, (rs, i) -> new Ref(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), (Long) rs.getObject(6)), kind.name(), ownerId).stream().findFirst();
    }

    /** Public URL of the original, or of the {@code size} copy ("thumb" or "standard") when it exists. */
    public String url(Ref r, String size) {
        String key = "thumb".equals(size) && r.thumbKey() != null ? r.thumbKey() : "standard".equals(size) && r.standardKey() != null ? r.standardKey() : r.objectKey();
        return props.publicUrl(key);
    }

    public Optional<byte[]> bytes(Ref r) { return r2().get(r.objectKey()); }
    public Optional<byte[]> bytesOfKey(String key) { return r2().get(key); }

    /** Bytes of a logo wherever they live: R2 when verified, otherwise the database column the caller passes in. */
    public Optional<byte[]> logoBytes(Kind kind, UUID businessId, byte[] dbBytes) {
        {
            Optional<Ref> r = verifiedRef(kind, businessId);
            if (r.isPresent()) {
                try { Optional<byte[]> b = bytes(r.get()); if (b.isPresent()) return b; } catch (RuntimeException e) { log.warn("Logo not readable from R2, using the database copy: {}", e.getMessage()); }
            }
        }
        return Optional.ofNullable(dbBytes).filter(b -> b.length > 0);
    }

    /** True when a verified R2 copy exists (used for "has a logo" when the database column is empty). */
    public boolean hasVerified(Kind kind, UUID ownerId) { return verifiedRef(kind, ownerId).isPresent(); }

    /** Forgets an image: removes its R2 objects (best effort) and its reference. Database bytes are removed by the caller's own delete. */
    public void remove(Kind kind, UUID ownerId) {
        ref(kind, ownerId).ifPresent(r -> afterCommit(() -> deleteObjects(r, null, null, null)));
        jdbc.update("DELETE FROM media_objects WHERE kind = ? AND owner_id = ?", kind.name(), ownerId);
    }

    /** Object deletion waits for the database transaction to commit, so a rolled-back change never loses a stored picture. */
    private static void afterCommit(Runnable r) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else r.run();
    }

    private void deleteObjects(Ref r, String keepA, String keepB, String keepC) {
        for (String k : new String[] {r.objectKey(), r.thumbKey(), r.standardKey()}) {
            if (k == null || k.equals(keepA) || k.equals(keepB) || k.equals(keepC) || !r2Active()) continue;
            try { r2().delete(k); } catch (RuntimeException e) { log.warn("Could not delete an old object: {}", e.getMessage()); }
        }
    }

    void event(UUID businessId, Kind kind, String event, String detail) {
        try {
            jdbc.update("INSERT INTO media_events (id, business_id, kind, event, detail) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), businessId, kind == null ? null : kind.name(), event,
                detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)));
        } catch (RuntimeException e) { log.warn("Could not record a media event: {}", e.getMessage()); }
    }

    /** Writes then reads then deletes a small probe object. Used by the Storage page's "test connection". Returns a plain message, never credentials. */
    public String testConnection() {
        if (!props.configured()) return "Not configured. Missing: " + String.join(", ", props.missing()) + (props.problems().isEmpty() ? "" : ". Problems: " + String.join("; ", props.problems()));
        String key = "healthcheck/probe-" + UUID.randomUUID() + ".txt";
        try {
            byte[] body = ("shopscale " + System.nanoTime()).getBytes();
            r2().put(key, body, "text/plain");
            boolean ok = r2().get(key).map(b -> java.util.Arrays.equals(b, body)).orElse(false);
            r2().delete(key);
            return ok ? "OK: wrote, read back and deleted a test object." : "FAILED: the object read back did not match.";
        } catch (RuntimeException e) { return "FAILED: " + e.getMessage(); }
    }
}
