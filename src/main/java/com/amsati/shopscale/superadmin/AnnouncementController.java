package com.amsati.shopscale.superadmin;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.Tenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Platform announcements. Delivery is real and simple: the merchant app asks /api/announcements/active, which returns the announcements that are
 * PUBLISHED, inside their start/end window and aimed at this business (everyone, or businesses currently on a given plan). Scheduling is just the window.
 * No email or push is sent.
 */
@RestController
public class AnnouncementController {
    private final JdbcTemplate jdbc;
    private final AdminAudit audit;
    private final SuperAdminAccess access;

    public AnnouncementController(JdbcTemplate jdbc, AdminAudit audit, SuperAdminAccess access) { this.jdbc = jdbc; this.audit = audit; this.access = access; }

    public record Request(@NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 2000) String body, @Pattern(regexp = "INFO|WARNING|CRITICAL") String level,
                          @Size(max = 40) String audience, Instant startsAt, Instant endsAt) {}

    // ---------- merchants ----------
    @GetMapping("/api/announcements/active")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> active() {
        UUID bid = Tenant.businessId();
        return AdminSql.rows(jdbc, """
            select a.id, a.title, a.body, a.level, coalesce(a.starts_at, a.updated_at) as starts_at from platform_announcements a
            where a.status = 'PUBLISHED' and (a.starts_at is null or a.starts_at <= now()) and (a.ends_at is null or a.ends_at > now())
              and (a.audience = 'ALL' or a.audience = 'PLAN:' || coalesce((select s.plan_code from subscriptions s where s.business_id = ?), ''))
            order by (a.level = 'CRITICAL') desc, a.updated_at desc limit 5""", bid);
    }

    // ---------- Super Admin ----------
    @GetMapping("/api/super-admin/announcements")
    @PreAuthorize("@superAdmin.ok()")
    public List<Map<String, Object>> list() {
        return AdminSql.rows(jdbc, """
            select id, title, body, level, audience, status, starts_at, ends_at, created_at, updated_at,
                   case when status <> 'PUBLISHED' then lower(status)
                        when ends_at is not null and ends_at <= now() then 'ended'
                        when starts_at is not null and starts_at > now() then 'scheduled' else 'live' end as state
            from platform_announcements order by created_at desc limit 200""");
    }

    private String audience(String a) {
        String v = a == null || a.isBlank() ? "ALL" : a.trim().toUpperCase();
        if (v.equals("ALL")) return v;
        if (v.startsWith("PLAN:")) {
            Integer n = jdbc.queryForObject("select count(*) from plans where code = ?", Integer.class, v.substring(5));
            if (n != null && n > 0) return v;
        }
        throw ApiException.badRequest("Audience must be ALL or PLAN:<an existing plan code>");
    }

    private void window(Instant s, Instant e) { if (s != null && e != null && !e.isAfter(s)) throw ApiException.badRequest("The end time must be after the start time"); }

    @PostMapping("/api/super-admin/announcements")
    @PreAuthorize("@superAdmin.ok()")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody Request r) {
        window(r.startsAt(), r.endsAt());
        UUID id = UUID.randomUUID();
        jdbc.update("insert into platform_announcements (id, title, body, level, audience, starts_at, ends_at, created_by) values (?,?,?,?,?,?,?,?)", id, r.title().trim(), r.body().trim(),
            r.level() == null ? "INFO" : r.level(), audience(r.audience()), ts(r.startsAt()), ts(r.endsAt()), access.current().userId());
        audit.ok("ANNOUNCEMENT_CREATED", "announcement", id, null, null, null);
        return Map.of("id", id, "status", "DRAFT");
    }

    @PutMapping("/api/super-admin/announcements/{id}")
    @PreAuthorize("@superAdmin.ok()")
    @Transactional
    public Map<String, Object> edit(@PathVariable UUID id, @Valid @RequestBody Request r) {
        window(r.startsAt(), r.endsAt());
        int n = jdbc.update("update platform_announcements set title = ?, body = ?, level = ?, audience = ?, starts_at = ?, ends_at = ?, updated_at = now() where id = ?", r.title().trim(), r.body().trim(),
            r.level() == null ? "INFO" : r.level(), audience(r.audience()), ts(r.startsAt()), ts(r.endsAt()), id);
        if (n == 0) throw ApiException.notFound("Announcement not found");
        audit.ok("ANNOUNCEMENT_EDITED", "announcement", id, null, null, null);
        return Map.of("ok", true);
    }

    @PostMapping("/api/super-admin/announcements/{id}/publish")
    @PreAuthorize("@superAdmin.ok()")
    @Transactional
    public Map<String, Object> publish(@PathVariable UUID id) { return setStatus(id, "PUBLISHED", "ANNOUNCEMENT_PUBLISHED"); }

    @PostMapping("/api/super-admin/announcements/{id}/deactivate")
    @PreAuthorize("@superAdmin.ok()")
    @Transactional
    public Map<String, Object> deactivate(@PathVariable UUID id) { return setStatus(id, "INACTIVE", "ANNOUNCEMENT_DEACTIVATED"); }

    private Map<String, Object> setStatus(UUID id, String status, String action) {
        int n = jdbc.update("update platform_announcements set status = ?, updated_at = now() where id = ? and status <> ?", status, id, status);
        if (n == 0) throw ApiException.conflict("Announcement not found or already " + status.toLowerCase());
        audit.ok(action, "announcement", id, null, null, null);
        return Map.of("ok", true, "status", status);
    }

    private static java.sql.Timestamp ts(Instant i) { return i == null ? null : java.sql.Timestamp.from(i); }
}
