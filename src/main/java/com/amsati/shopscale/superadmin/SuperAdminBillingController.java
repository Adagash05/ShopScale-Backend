package com.amsati.shopscale.superadmin;

import com.amsati.shopscale.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Subscriptions and billing transactions, read from the existing billing tables. Nothing here creates, edits or marks a payment:
 * payments change only through Paystack verification in BillingService. The one correction offered is a complimentary period extension.
 */
@RestController
@RequestMapping("/api/super-admin")
@PreAuthorize("@superAdmin.ok()")
public class SuperAdminBillingController {
    private static final Map<String, String> SUB_SORT = new LinkedHashMap<>();
    private static final Map<String, String> TX_SORT = new LinkedHashMap<>();
    static {
        SUB_SORT.put("renewal", "s.current_period_end"); SUB_SORT.put("created", "s.created_at"); SUB_SORT.put("business", "lower(b.name)"); SUB_SORT.put("plan", "s.plan_code"); SUB_SORT.put("status", "s.status");
        TX_SORT.put("created", "p.created_at"); TX_SORT.put("amount", "p.amount"); TX_SORT.put("paid", "p.paid_at"); TX_SORT.put("status", "p.status");
    }
    private final JdbcTemplate jdbc;
    private final AdminAudit audit;

    public SuperAdminBillingController(JdbcTemplate jdbc, AdminAudit audit) { this.jdbc = jdbc; this.audit = audit; }

    private static final String SUB_FROM = "subscriptions s join businesses b on b.id = s.business_id";
    private static final String SUB_COLS = """
        s.id, s.business_id, b.name as business_name, b.status as business_status, s.plan_code, s.billing_cycle, s.status, s.current_period_start, s.current_period_end,
        s.auto_renew, s.cancelled_at, s.created_at,
        (select p.status from subscription_payments p where p.business_id = s.business_id order by p.created_at desc limit 1) as last_payment_status,
        (select p.failure_note from subscription_payments p where p.business_id = s.business_id and p.status = 'FAILED' order by p.created_at desc limit 1) as last_failure,
        (select r.status from subscription_renewals r where r.business_id = s.business_id order by r.created_at desc limit 1) as last_renewal_status""";

    @GetMapping("/subscriptions")
    public Map<String, Object> subscriptions(@RequestParam(required = false) String q, @RequestParam(required = false) String plan, @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String attention, @RequestParam(defaultValue = "renewal") String sort, @RequestParam(defaultValue = "asc") String dir,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        AdminSql.Where w = new AdminSql.Where();
        if (q != null && !q.isBlank()) { String l = AdminSql.like(q); w.and("(lower(b.name) like ? escape '\\' or b.id::text like ? escape '\\')", l, l); }
        w.andIf(plan, "s.plan_code = ?", plan == null ? null : plan.toUpperCase());
        w.andIf(status, "s.status = ?", status == null ? null : status.toUpperCase());
        if ("payment_failed".equalsIgnoreCase(attention)) w.and("exists (select 1 from subscription_renewals r where r.business_id = s.business_id and r.status = 'FAILED' and r.created_at > now() - interval '30 days') or s.status in ('PAST_DUE','GRACE_PERIOD')");
        return AdminSql.page(jdbc, SUB_COLS, SUB_FROM, w, AdminSql.order(SUB_SORT, sort, dir), page, size);
    }

    @GetMapping("/subscriptions/{businessId}")
    public Map<String, Object> subscription(@PathVariable UUID businessId) {
        Map<String, Object> s = AdminSql.one(jdbc, "select " + SUB_COLS + " from " + SUB_FROM + " where s.business_id = ?", businessId);
        if (s == null) throw ApiException.notFound("No subscription for this business");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subscription", s);
        out.put("payments", AdminSql.rows(jdbc, "select reference, kind, plan_code, billing_cycle, amount, list_price, credit, currency, status, paid_at, period_start, period_end, failure_note, source, created_at from subscription_payments where business_id = ? order by created_at desc limit 50", businessId));
        out.put("renewals", AdminSql.rows(jdbc, "select due_at, attempt_no, status, failure_reason, next_retry_at, finished_at from subscription_renewals where business_id = ? order by created_at desc limit 20", businessId));
        // Only whether a reusable card is on file. Card data and Paystack authorization codes are never read here.
        Integer cards = jdbc.queryForObject("select count(*) from billing_authorizations where business_id = ? and active and reusable", Integer.class, businessId);
        out.put("hasSavedCard", cards != null && cards > 0);
        return out;
    }

    public record ExtendRequest(@NotNull @Min(1) @Max(90) Integer days, @NotBlank @Size(min = 3, max = 500) String reason) {}

    /** A complimentary extension of the paid-through date. It records no payment and marks nothing as paid. */
    @PostMapping("/subscriptions/{businessId}/extend")
    @Transactional
    public Map<String, Object> extend(@PathVariable UUID businessId, @Valid @RequestBody ExtendRequest r) {
        Map<String, Object> before = AdminSql.one(jdbc, "select status, plan_code, current_period_end from subscriptions where business_id = ? for update", businessId);
        if (before == null) throw ApiException.notFound("No subscription for this business");
        String st = (String) before.get("status");
        if (!List.of("TRIALING", "ACTIVE", "PAST_DUE", "GRACE_PERIOD", "EXPIRED").contains(st)) throw ApiException.conflict("A " + st.toLowerCase() + " subscription cannot be extended. The customer has to subscribe again.");
        jdbc.update("""
            update subscriptions set current_period_end = greatest(current_period_end, now()) + make_interval(days => ?),
                   status = case when status in ('PAST_DUE','GRACE_PERIOD','EXPIRED') then 'ACTIVE' else status end, updated_at = now()
            where business_id = ?""", r.days(), businessId);
        Map<String, Object> after = AdminSql.one(jdbc, "select status, current_period_end from subscriptions where business_id = ?", businessId);
        audit.ok("SUBSCRIPTION_EXTENDED", "subscription", businessId, businessId, r.reason().trim(),
            Map.of("days", r.days(), "statusBefore", st, "periodEndBefore", String.valueOf(before.get("currentPeriodEnd")), "statusAfter", String.valueOf(after.get("status")), "periodEndAfter", String.valueOf(after.get("currentPeriodEnd"))));
        return Map.of("ok", true, "status", after.get("status"), "currentPeriodEnd", after.get("currentPeriodEnd"));
    }

    @GetMapping("/transactions")
    public Map<String, Object> transactions(@RequestParam(required = false) String q, @RequestParam(required = false) String status, @RequestParam(required = false) String kind,
                                            @RequestParam(required = false) String source, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                            @RequestParam(defaultValue = "created") String sort, @RequestParam(defaultValue = "desc") String dir,
                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        AdminSql.Where w = new AdminSql.Where();
        if (q != null && !q.isBlank()) { String l = AdminSql.like(q); w.and("(lower(p.reference) like ? escape '\\' or lower(b.name) like ? escape '\\')", l, l); }
        w.andIf(status, "p.status = ?", status == null ? null : status.toUpperCase());
        w.andIf(kind, "p.kind = ?", kind == null ? null : kind.toUpperCase());
        w.andIf(source, "p.source = ?", source == null ? null : source.toUpperCase());
        w.andIf(from, "p.created_at >= ?::timestamptz", from);
        w.andIf(to, "p.created_at < (?::date + 1)::timestamptz", to);
        String cols = "p.id, p.reference, p.business_id, b.name as business_name, p.plan_code, p.billing_cycle, p.kind, p.amount, p.currency, p.status, p.paid_at, p.created_at, p.failure_note, p.source, 'Paystack' as provider";
        return AdminSql.page(jdbc, cols, "subscription_payments p join businesses b on b.id = p.business_id", w, AdminSql.order(TX_SORT, sort, dir), page, size);
    }

    /** Money actually collected (verified SUCCESS payments), month by month. Expected recurring revenue is on the dashboard and is a different number. */
    @GetMapping("/transactions/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectedByMonth", AdminSql.rows(jdbc, """
            select to_char(date_trunc('month', paid_at), 'YYYY-MM') as month, coalesce(sum(amount), 0) as collected, count(*) as payments
            from subscription_payments where status = 'SUCCESS' and paid_at >= date_trunc('month', now()) - interval '11 months' group by 1 order by 1"""));
        out.put("totals", AdminSql.one(jdbc, """
            select coalesce(sum(amount) filter (where status = 'SUCCESS'), 0) as collected_all_time,
                   count(*) filter (where status = 'SUCCESS') as successful, count(*) filter (where status = 'FAILED') as failed, count(*) filter (where status = 'MISMATCH') as mismatched
            from subscription_payments"""));
        return out;
    }
}
