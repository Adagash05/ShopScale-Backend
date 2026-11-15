package com.amsati.shopscale.superadmin;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.BusinessStatusCache;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Every business on the platform, read across tenants. Suspension only blocks access: it never deletes or edits business data. */
@RestController
@RequestMapping("/api/super-admin/businesses")
@PreAuthorize("@superAdmin.ok()")
public class SuperAdminBusinessController {
    private static final Map<String, String> SORT = new LinkedHashMap<>();
    static {
        SORT.put("created", "b.created_at"); SORT.put("name", "lower(b.name)"); SORT.put("status", "b.status");
        SORT.put("plan", "s.plan_code"); SORT.put("owner", "lower(o.full_name)"); SORT.put("lastActive", "last_activity");
    }
    private static final String FROM = """
        businesses b
        left join lateral (select u.id, u.full_name, u.email, u.phone from memberships m join users u on u.id = m.user_id
                           where m.business_id = b.id and m.role = 'OWNER' and m.active order by m.created_at limit 1) o on true
        left join subscriptions s on s.business_id = b.id""";
    private static final String COLS = """
        b.id, b.name, b.slug, b.business_type, b.subtype, b.status, b.status_reason, b.created_at, b.phone as business_phone,
        o.id as owner_id, o.full_name as owner_name, o.email as owner_email, o.phone as owner_phone,
        s.plan_code, s.status as subscription_status, s.billing_cycle, s.current_period_end, s.auto_renew,
        (select count(*) from branches br where br.business_id = b.id and br.active) as branch_count,
        (select count(*) from memberships m where m.business_id = b.id and m.active) as employee_count,
        (select max(m.last_active_at) from memberships m where m.business_id = b.id) as last_activity""";

    private final JdbcTemplate jdbc;
    private final AdminAudit audit;
    private final BusinessStatusCache statusCache;

    public SuperAdminBusinessController(JdbcTemplate jdbc, AdminAudit audit, BusinessStatusCache statusCache) { this.jdbc = jdbc; this.audit = audit; this.statusCache = statusCache; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status, @RequestParam(required = false) String plan,
                                    @RequestParam(required = false) String bucket, @RequestParam(required = false) String industry,
                                    @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                    @RequestParam(defaultValue = "created") String sort, @RequestParam(defaultValue = "desc") String dir,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        AdminSql.Where w = new AdminSql.Where();
        if (q != null && !q.isBlank()) {
            String l = AdminSql.like(q);
            w.and("(lower(b.name) like ? escape '\\' or b.id::text like ? escape '\\' or lower(coalesce(o.full_name,'')) like ? escape '\\' or lower(coalesce(o.email,'')) like ? escape '\\' or coalesce(o.phone,'') like ? escape '\\')", l, l, l, l, l);
        }
        w.andIf(status, "b.status = ?", status == null ? null : status.toUpperCase());
        w.andIf(plan, "s.plan_code = ?", plan == null ? null : plan.toUpperCase());
        w.andIf(industry, "b.business_type = ?", industry == null ? null : industry.toUpperCase());
        if (bucket != null) switch (bucket.toLowerCase()) {
            case "trial" -> w.and("s.status = 'TRIALING'");
            case "paid" -> w.and("s.status in ('ACTIVE','PAST_DUE','GRACE_PERIOD') and s.plan_code <> 'FREE'");
            case "expired" -> w.and("s.status = 'EXPIRED'");
            case "cancelled" -> w.and("s.status = 'CANCELLED'");
            default -> { }
        }
        w.andIf(from, "b.created_at >= ?::timestamptz", from);
        w.andIf(to, "b.created_at < (?::date + 1)::timestamptz", to);
        return AdminSql.page(jdbc, COLS, FROM, w, AdminSql.order(SORT, sort, dir), page, size);
    }

    /** The values the filters offer (industries and plans that actually exist). */
    @GetMapping("/filters")
    public Map<String, Object> filters() {
        return Map.of("industries", jdbc.queryForList("select distinct business_type from businesses order by 1", String.class),
            "plans", jdbc.queryForList("select code from plans order by sort_order", String.class));
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        Map<String, Object> b = AdminSql.one(jdbc, "select " + COLS + ", b.email as business_email, b.address, b.description, b.currency, b.timezone, b.status_changed_at from " + FROM + " where b.id = ?", id);
        if (b == null) throw ApiException.notFound("Business not found");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business", b);
        out.put("members", AdminSql.rows(jdbc, """
            select u.id as user_id, u.full_name, u.email, u.phone, u.active as user_active, m.role, m.active as membership_active, m.created_at, m.last_active_at
            from memberships m join users u on u.id = m.user_id where m.business_id = ? order by (m.role = 'OWNER') desc, m.created_at""", id));
        out.put("branches", AdminSql.rows(jdbc, "select id, name, address, phone, is_main, active, created_at from branches where business_id = ? order by is_main desc, created_at", id));
        out.put("subscription", AdminSql.one(jdbc, "select plan_code, billing_cycle, status, current_period_start, current_period_end, auto_renew, cancelled_at, created_at from subscriptions where business_id = ?", id));
        out.put("payments", AdminSql.rows(jdbc, "select reference, kind, plan_code, billing_cycle, amount, currency, status, paid_at, failure_note, source, created_at from subscription_payments where business_id = ? order by created_at desc limit 25", id));
        out.put("catalogue", AdminSql.one(jdbc, """
            select (select count(*) from products where business_id = ? and status = 'ACTIVE') as active_products,
                   (select count(*) from product_variants where business_id = ? and active) as active_variants,
                   (select count(*) from product_variants where business_id = ? and active and quantity <= low_stock_threshold) as low_stock_variants,
                   (select coalesce(sum(quantity * cost), 0) from product_variants where business_id = ? and active) as stock_value_at_cost""", id, id, id, id));
        out.put("sales", AdminSql.one(jdbc, """
            select count(*) filter (where status = 'COMPLETED') as completed_sales,
                   coalesce(sum(total) filter (where status = 'COMPLETED'), 0) as completed_total,
                   count(*) filter (where created_at >= now() - interval '30 days' and status = 'COMPLETED') as sales_last_30_days,
                   coalesce(sum(total) filter (where created_at >= now() - interval '30 days' and status = 'COMPLETED'), 0) as total_last_30_days,
                   max(created_at) as last_sale_at
            from sales where business_id = ?""", id));
        out.put("referral", AdminSql.one(jdbc, """
            select a.id as partner_id, a.full_name as partner_name, a.code, r.attributed_at, r.first_paid_at, r.eligible_until
            from affiliate_referrals r join affiliates a on a.id = r.affiliate_id
            where r.referred_user_id = (select u.id from memberships m join users u on u.id = m.user_id where m.business_id = ? and m.role = 'OWNER' order by m.created_at limit 1)""", id));
        out.put("activity", AdminSql.rows(jdbc, "select action, entity, detail, created_at from audit_logs where business_id = ? order by created_at desc limit 25", id));
        out.put("support", AdminSql.rows(jdbc, "select id, category, priority, status, subject, created_at, updated_at from support_tickets where business_id = ? order by updated_at desc limit 20", id));
        out.put("adminHistory", AdminSql.rows(jdbc, "select action, actor_email, success, reason, created_at from admin_audit_log where business_id = ? order by created_at desc limit 20", id));
        return out;
    }

    public record EditRequest(@NotBlank @Size(max = 150) String name, @Size(max = 40) String phone, @Size(max = 255) String email, @Size(max = 500) String address,
                              @Size(max = 2000) String description, @NotBlank @Size(min = 3, max = 500) String reason) {}

    /** Profile fields only. The slug, id, currency, counters and every financial record are not editable here. */
    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> edit(@PathVariable UUID id, @Valid @RequestBody EditRequest r) {
        Map<String, Object> before = AdminSql.one(jdbc, "select name, phone, email, address, description from businesses where id = ?", id);
        if (before == null) throw ApiException.notFound("Business not found");
        jdbc.update("update businesses set name = ?, phone = ?, email = ?, address = ?, description = ? where id = ?", r.name().trim(), blank(r.phone()), blank(r.email()), blank(r.address()), blank(r.description()), id);
        List<String> changed = new ArrayList<>();
        Map<String, String> now = new LinkedHashMap<>();
        now.put("name", r.name().trim()); now.put("phone", blank(r.phone())); now.put("email", blank(r.email())); now.put("address", blank(r.address())); now.put("description", blank(r.description()));
        for (var e : now.entrySet()) if (!Objects.equals(before.get(e.getKey()), e.getValue())) changed.add(e.getKey());
        audit.ok("BUSINESS_EDITED", "business", id, id, r.reason().trim(), Map.of("fieldsChanged", changed));
        return Map.of("ok", true, "fieldsChanged", changed);
    }

    public record ReasonRequest(@NotBlank @Size(min = 3, max = 500) String reason) {}

    @PostMapping("/{id}/suspend")
    @Transactional
    public Map<String, Object> suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        int n = jdbc.update("update businesses set status = 'SUSPENDED', status_reason = ?, status_changed_at = now() where id = ? and status <> 'SUSPENDED'", r.reason().trim(), id);
        if (n == 0) throw exists(id) ? ApiException.conflict("This business is already suspended") : ApiException.notFound("Business not found");
        statusCache.evict(id);
        audit.ok("BUSINESS_SUSPENDED", "business", id, id, r.reason().trim(), null);
        return Map.of("ok", true, "status", "SUSPENDED");
    }

    @PostMapping("/{id}/reactivate")
    @Transactional
    public Map<String, Object> reactivate(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        int n = jdbc.update("update businesses set status = 'ACTIVE', status_reason = null, status_changed_at = now() where id = ? and status = 'SUSPENDED'", id);
        if (n == 0) throw exists(id) ? ApiException.conflict("This business is not suspended") : ApiException.notFound("Business not found");
        statusCache.evict(id);
        audit.ok("BUSINESS_REACTIVATED", "business", id, id, r.reason().trim(), null);
        return Map.of("ok", true, "status", "ACTIVE");
    }

    private boolean exists(UUID id) { Integer c = jdbc.queryForObject("select count(*) from businesses where id = ?", Integer.class, id); return c != null && c > 0; }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
