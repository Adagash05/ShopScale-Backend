package com.amsati.shopscale.superadmin;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Read-only view of the administrative audit log. There is deliberately no way to change or delete an entry. */
@RestController
@RequestMapping("/api/super-admin/audit-logs")
@PreAuthorize("@superAdmin.ok()")
public class SuperAdminAuditController {
    private final JdbcTemplate jdbc;

    public SuperAdminAuditController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) String action, @RequestParam(required = false) String success,
                                    @RequestParam(required = false) String targetType, @RequestParam(required = false) UUID businessId,
                                    @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        AdminSql.Where w = new AdminSql.Where();
        if (q != null && !q.isBlank()) { String l = AdminSql.like(q); w.and("(lower(coalesce(actor_email,'')) like ? escape '\\' or lower(coalesce(reason,'')) like ? escape '\\' or coalesce(target_id,'') like ? escape '\\')", l, l, l); }
        w.andIf(action, "action = ?", action == null ? null : action.toUpperCase());
        w.andIf(targetType, "target_type = ?", targetType);
        if (businessId != null) w.and("business_id = ?", businessId);
        if ("true".equalsIgnoreCase(success)) w.and("success"); else if ("false".equalsIgnoreCase(success)) w.and("not success");
        w.andIf(from, "created_at >= ?::timestamptz", from);
        w.andIf(to, "created_at < (?::date + 1)::timestamptz", to);
        return AdminSql.page(jdbc, "id, actor_id, actor_email, action, target_type, target_id, business_id, success, reason, ip, metadata, created_at", "admin_audit_log", w, "created_at desc", page, size);
    }

    @GetMapping("/actions")
    public List<String> actions() { return jdbc.queryForList("select distinct action from admin_audit_log order by 1", String.class); }
}
