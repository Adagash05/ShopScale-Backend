package com.amsati.shopscale.superadmin;

import com.amsati.shopscale.media.MediaMigrationService;
import com.amsati.shopscale.media.MediaProperties;
import com.amsati.shopscale.media.MediaService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Storage dashboard and the controlled image migration. Every action that changes anything is audited. There is deliberately no "delete all" action:
 * orphan clean-up takes an explicit list of keys and re-checks each one is unreferenced.
 */
@RestController
@RequestMapping("/api/super-admin/storage")
@PreAuthorize("@superAdmin.ok()")
public class SuperAdminStorageController {
    private final JdbcTemplate jdbc;
    private final MediaProperties props;
    private final MediaService media;
    private final MediaMigrationService migration;
    private final AdminAudit audit;

    public SuperAdminStorageController(JdbcTemplate jdbc, MediaProperties props, MediaService media, MediaMigrationService migration, AdminAudit audit) {
        this.jdbc = jdbc; this.props = props; this.media = media; this.migration = migration; this.audit = audit;
    }

    public record KeysRequest(List<String> keys) {}

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> o = new LinkedHashMap<>();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("configured", props.configured());
        cfg.put("active", props.r2Allowed());
        cfg.put("mode", props.getMode());
        cfg.put("serveMode", props.proxy() ? "PROXY" : "REDIRECT");
        cfg.put("missing", props.missing());
        cfg.put("problems", props.problems());
        cfg.put("bucket", props.getBucketName() == null || props.getBucketName().isBlank() ? null : props.getBucketName());
        cfg.put("publicBaseUrl", props.getPublicBaseUrl() == null || props.getPublicBaseUrl().isBlank() ? null : props.getPublicBaseUrl());
        o.put("config", cfg);
        o.put("byKind", jdbc.queryForList("""
            select kind, storage, status, count(*) as items, coalesce(sum(size_bytes), 0) as bytes from media_objects group by kind, storage, status order by kind, storage, status"""));
        o.put("legacy", jdbc.queryForMap("""
            select (select count(*) from product_images where data is not null) as product_images,
                   (select coalesce(sum(size_bytes), 0) from product_images where data is not null) as product_bytes,
                   (select count(*) from store_logos where data is not null) as store_logos,
                   (select coalesce(sum(length(data)), 0) from store_logos where data is not null) as store_logo_bytes,
                   (select count(*) from invoice_settings where logo is not null and length(logo) > 0) as invoice_logos,
                   (select coalesce(sum(length(logo)), 0) from invoice_settings where logo is not null) as invoice_logo_bytes"""));
        o.put("r2", jdbc.queryForMap("""
            select count(*) filter (where storage = 'R2' and status = 'VERIFIED') as verified_objects,
                   coalesce(sum(size_bytes) filter (where storage = 'R2' and status = 'VERIFIED'), 0) as verified_original_bytes,
                   count(*) filter (where status = 'FAILED') as failed,
                   count(*) filter (where status = 'PENDING' and storage = 'DB') as pending,
                   count(*) filter (where thumb_key is not null) as with_thumb,
                   count(*) filter (where standard_key is not null) as with_standard from media_objects"""));
        o.put("undiscovered", jdbc.queryForObject("""
            select (select count(*) from product_images p where p.data is not null and not exists (select 1 from media_objects m where m.kind = 'PRODUCT_IMAGE' and m.owner_id = p.id))
                 + (select count(*) from store_logos l where l.data is not null and not exists (select 1 from media_objects m where m.kind = 'STORE_LOGO' and m.owner_id = l.business_id))
                 + (select count(*) from invoice_settings i where i.logo is not null and length(i.logo) > 0 and not exists (select 1 from media_objects m where m.kind = 'INVOICE_LOGO' and m.owner_id = i.business_id))""", Long.class));
        o.put("lastActivity", jdbc.queryForList("select event, kind, business_id, detail, created_at from media_events order by created_at desc limit 20"));
        o.put("recentFailures", jdbc.queryForList("select event, kind, business_id, detail, created_at from media_events where event in ('UPLOAD_FAILED','MIGRATE_FAILED','VERIFY_FAILED') order by created_at desc limit 20"));
        o.put("running", migration.isRunning());
        o.put("usageNote", "Sizes are the sizes of original files recorded in the database. Resized copies are not included. They are not the figures on your Cloudflare bill; check the Cloudflare dashboard for provider-reported usage.");
        return o;
    }

    @GetMapping("/items")
    public Map<String, Object> items(@RequestParam(required = false) String status, @RequestParam(required = false) String kind, @RequestParam(required = false) String storage,
                                     @RequestParam(required = false) UUID businessId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        AdminSql.Where w = new AdminSql.Where();
        w.andIf(status, "status = ?", status == null ? null : status.toUpperCase());
        w.andIf(kind, "kind = ?", kind == null ? null : kind.toUpperCase());
        w.andIf(storage, "storage = ?", storage == null ? null : storage.toUpperCase());
        if (businessId != null) w.and("business_id = ?", businessId);
        return AdminSql.page(jdbc, "id, business_id, kind, owner_id, storage, object_key, thumb_key, standard_key, content_type, size_bytes, sha256, status, attempts, last_error, verified_at, source, updated_at", "media_objects", w, "updated_at desc", page, size);
    }

    @GetMapping("/consistency")
    public Map<String, Object> consistency() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("productImageRowsWithoutImage", jdbc.queryForObject("select count(*) from media_objects m where m.kind = 'PRODUCT_IMAGE' and not exists (select 1 from product_images p where p.id = m.owner_id)", Long.class));
        o.put("r2OnlyProductImagesWithoutReference", jdbc.queryForObject("select count(*) from product_images p where p.data is null and not exists (select 1 from media_objects m where m.kind = 'PRODUCT_IMAGE' and m.owner_id = p.id and m.storage = 'R2' and m.status = 'VERIFIED')", Long.class));
        o.put("failedWithoutDatabaseCopy", jdbc.queryForObject("""
            select count(*) from media_objects m where m.status = 'FAILED' and m.kind = 'PRODUCT_IMAGE' and not exists (select 1 from product_images p where p.id = m.owner_id and p.data is not null)""", Long.class));
        o.put("note", "Run 'Verify objects' to compare the recorded sizes with what is actually in R2. List orphans to find R2 objects that no record refers to.");
        return o;
    }

    @PostMapping("/test-connection")
    public Map<String, Object> test() {
        String r = media.testConnection();
        audit.record("STORAGE_TEST_CONNECTION", "storage", "r2", null, r.startsWith("OK"), r.startsWith("OK") ? null : r, null);
        return Map.of("ok", r.startsWith("OK"), "message", r);
    }

    @PostMapping("/migration/discover")
    public Map<String, Object> discover() {
        int n = migration.discover();
        audit.ok("STORAGE_MIGRATION_DISCOVER", "storage", "media", null, null, Map.of("registered", n));
        return Map.of("registered", n);
    }

    @PostMapping("/migration/run")
    public MediaMigrationService.BatchResult run(@RequestParam(defaultValue = "25") int limit) {
        MediaMigrationService.BatchResult r = migration.runBatch(limit);
        audit.ok("STORAGE_MIGRATION_BATCH", "storage", "media", null, r.note(), Map.of("found", r.found(), "migrated", r.migrated(), "failed", r.failed()));
        return r;
    }

    @PostMapping("/migration/retry-failed")
    public Map<String, Object> retry() {
        int n = migration.retryFailed();
        audit.ok("STORAGE_MIGRATION_RETRY", "storage", "media", null, null, Map.of("requeued", n));
        return Map.of("requeued", n);
    }

    @PostMapping("/migration/verify")
    public MediaMigrationService.VerifyResult verify(@RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "false") boolean deep) {
        MediaMigrationService.VerifyResult r = migration.verify(limit, deep);
        audit.ok("STORAGE_VERIFY", "storage", "media", null, null, Map.of("checked", r.checked(), "bad", r.bad(), "deep", deep));
        return r;
    }

    @PostMapping("/migration/rollback")
    public MediaMigrationService.BatchResult rollback(@RequestParam(defaultValue = "25") int limit) {
        MediaMigrationService.BatchResult r = migration.rollback(limit);
        audit.ok("STORAGE_ROLLBACK_BATCH", "storage", "media", null, r.note(), Map.of("found", r.found(), "restored", r.migrated(), "failed", r.failed()));
        return r;
    }

    @GetMapping("/orphans")
    public MediaMigrationService.Orphans orphans() {
        if (!props.configured()) return new MediaMigrationService.Orphans(List.of(), 0, false, 0);
        return migration.findOrphans(500);
    }

    @PostMapping("/orphans/delete")
    public Map<String, Object> deleteOrphans(@RequestBody KeysRequest r) {
        List<String> keys = r.keys() == null ? List.of() : r.keys().stream().limit(200).toList();
        int n = props.configured() ? migration.deleteOrphans(keys) : 0;
        audit.ok("STORAGE_ORPHANS_DELETED", "storage", "r2", null, null, Map.of("requested", keys.size(), "deleted", n));
        return Map.of("requested", keys.size(), "deleted", n);
    }
}
