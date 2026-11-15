package com.amsati.shopscale.superadmin;

import com.amsati.shopscale.auth.AccountService;
import com.amsati.shopscale.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Platform users across all businesses. Passwords are hashed and never returned; a reset only emails the user a link. */
@RestController
@RequestMapping("/api/super-admin/users")
@PreAuthorize("@superAdmin.ok()")
public class SuperAdminUserController {
    private static final Map<String, String> SORT = new LinkedHashMap<>();
    static { SORT.put("created", "u.created_at"); SORT.put("name", "lower(u.full_name)"); SORT.put("email", "lower(u.email)"); SORT.put("lastActive", "last_active_at"); }

    private final JdbcTemplate jdbc;
    private final AdminAudit audit;
    private final AccountService accounts;

    public SuperAdminUserController(JdbcTemplate jdbc, AdminAudit audit, AccountService accounts) { this.jdbc = jdbc; this.audit = audit; this.accounts = accounts; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status, @RequestParam(required = false) String role,
                                    @RequestParam(required = false) UUID businessId, @RequestParam(defaultValue = "created") String sort, @RequestParam(defaultValue = "desc") String dir,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        AdminSql.Where w = new AdminSql.Where();
        if (q != null && !q.isBlank()) { String l = AdminSql.like(q); w.and("(lower(u.full_name) like ? escape '\\' or lower(u.email) like ? escape '\\' or coalesce(u.phone,'') like ? escape '\\')", l, l, l); }
        if ("active".equalsIgnoreCase(status)) w.and("u.active"); else if ("suspended".equalsIgnoreCase(status)) w.and("not u.active");
        if (role != null && !role.isBlank()) w.and("exists (select 1 from memberships m where m.user_id = u.id and m.role = ?)", role.toUpperCase());
        if (businessId != null) w.and("exists (select 1 from memberships m where m.user_id = u.id and m.business_id = ?)", businessId);
        String cols = "u.id, u.full_name, u.email, u.phone, u.active, u.email_verified, u.created_at, u.platform_role, (select max(m.last_active_at) from memberships m where m.user_id = u.id) as last_active_at";
        Map<String, Object> page1 = AdminSql.page(jdbc, cols, "users u", w, AdminSql.order(SORT, sort, dir), page, size);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) page1.get("items");
        attachMemberships(items);
        return page1;
    }

    private void attachMemberships(List<Map<String, Object>> items) {
        if (items.isEmpty()) return;
        List<UUID> ids = items.stream().map(m -> UUID.fromString((String) m.get("id"))).toList();
        String marks = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        Map<String, List<Map<String, Object>>> byUser = new HashMap<>();
        for (Map<String, Object> m : AdminSql.rows(jdbc, "select m.user_id, m.business_id, b.name as business_name, b.status as business_status, m.role, m.active from memberships m join businesses b on b.id = m.business_id where m.user_id in (" + marks + ") order by m.created_at", ids.toArray()))
            byUser.computeIfAbsent((String) m.get("userId"), k -> new ArrayList<>()).add(m);
        for (Map<String, Object> it : items) it.put("businesses", byUser.getOrDefault((String) it.get("id"), List.of()));
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        Map<String, Object> u = AdminSql.one(jdbc, "select id, full_name, email, phone, active, email_verified, created_at, platform_role from users where id = ?", id);
        if (u == null) throw ApiException.notFound("User not found");
        attachMemberships(List.of(u));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", u);
        out.put("sessions", AdminSql.rows(jdbc, "select user_agent, session_started_at, created_at, expires_at, revoked_at from refresh_tokens where user_id = ? order by created_at desc limit 10", id));
        out.put("activity", AdminSql.rows(jdbc, "select business_id, action, entity, detail, created_at from audit_logs where user_id = ? order by created_at desc limit 25", id));
        out.put("affiliate", AdminSql.one(jdbc, "select id, status, code from affiliates where user_id = ?", id));
        out.put("adminHistory", AdminSql.rows(jdbc, "select action, actor_email, success, reason, created_at from admin_audit_log where target_type = 'user' and target_id = ? order by created_at desc limit 20", id.toString()));
        return out;
    }

    public record ReasonRequest(@NotBlank @Size(min = 3, max = 500) String reason) {}

    @PostMapping("/{id}/suspend")
    @Transactional
    public Map<String, Object> suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        requireOrdinary(id);
        int n = jdbc.update("update users set active = false where id = ? and active", id);
        if (n == 0) throw ApiException.conflict("This user is already suspended");
        int ended = jdbc.update("update refresh_tokens set revoked_at = now() where user_id = ? and revoked_at is null", id);
        audit.ok("USER_SUSPENDED", "user", id, null, r.reason().trim(), Map.of("signInsEnded", ended));
        return Map.of("ok", true, "active", false);
    }

    @PostMapping("/{id}/reactivate")
    @Transactional
    public Map<String, Object> reactivate(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        requireOrdinary(id);
        int n = jdbc.update("update users set active = true where id = ? and not active", id);
        if (n == 0) throw ApiException.conflict("This user is not suspended");
        audit.ok("USER_REACTIVATED", "user", id, null, r.reason().trim(), null);
        return Map.of("ok", true, "active", true);
    }

    /** Emails the user the normal reset link (valid for an hour). The administrator never sees the link or any password. */
    @PostMapping("/{id}/password-reset")
    @Transactional
    public Map<String, Object> passwordReset(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        requireOrdinary(id);
        String email = jdbc.queryForObject("select email from users where id = ?", String.class, id);
        accounts.forgotPassword(new AccountService.ForgotRequest(email));
        audit.ok("USER_PASSWORD_RESET_SENT", "user", id, null, r.reason().trim(), null);
        return Map.of("ok", true);
    }

    /** Super Admin accounts are changed only through the provisioning and account flows, never from this list. */
    private void requireOrdinary(UUID id) {
        List<String> role = jdbc.query("select coalesce(platform_role, '') from users where id = ?", (rs, i) -> rs.getString(1), id);
        if (role.isEmpty()) throw ApiException.notFound("User not found");
        if (!role.get(0).isEmpty()) throw ApiException.forbidden("Super Admin accounts cannot be changed from the user list");
    }
}
