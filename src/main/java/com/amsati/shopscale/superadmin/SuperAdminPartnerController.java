package com.amsati.shopscale.superadmin;

import com.amsati.shopscale.partners.AffiliateService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Partner administration for the Super Admin Console. The directory and figures are new (server-side paginated, all from the real partner tables);
 * everything that changes status, commissions or payouts is the EXISTING /api/admin/partners API (same services, same ledger rules), which the console calls
 * with its own token and which is recorded in the admin audit log by {@link AdminActionAuditInterceptor}. No second partner system exists.
 */
@RestController
@RequestMapping("/api/super-admin/partners")
@PreAuthorize("@superAdmin.ok()")
public class SuperAdminPartnerController {
    private static final Map<String, String> SORT = new LinkedHashMap<>();
    static {
        SORT.put("created", "a.created_at"); SORT.put("name", "lower(a.full_name)"); SORT.put("status", "a.status"); SORT.put("referrals", "total_referrals");
        SORT.put("conversions", "conversions"); SORT.put("earned", "commission_earned"); SORT.put("pending", "pending_commission"); SORT.put("payouts", "total_payouts");
    }
    private final JdbcTemplate jdbc;
    private final AffiliateService affiliates;
    private final AdminAudit audit;

    public SuperAdminPartnerController(JdbcTemplate jdbc, AffiliateService affiliates, AdminAudit audit) { this.jdbc = jdbc; this.affiliates = affiliates; this.audit = audit; }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partners", AdminSql.one(jdbc, """
            select count(*) as total, count(*) filter (where status = 'APPROVED') as active, count(*) filter (where status = 'SUSPENDED') as suspended,
                   count(*) filter (where status = 'PENDING') as pending_applications, count(*) filter (where status = 'REJECTED') as rejected from affiliates"""));
        out.put("referrals", AdminSql.one(jdbc, """
            select count(*) as total, count(distinct referred_user_id) as referred_users, count(*) filter (where first_paid_at is not null) as converted,
                   count(*) filter (where first_paid_at is null) as pending_conversion from affiliate_referrals"""));
        out.put("commissions", AdminSql.one(jdbc, """
            select coalesce(sum(amount - reversed_amount) filter (where kind <> 'CLAWBACK' and status <> 'CANCELLED'), 0) as earned,
                   coalesce(sum(amount - reversed_amount) filter (where status = 'PENDING'), 0) as pending,
                   coalesce(sum(amount - reversed_amount) filter (where status in ('AVAILABLE','RESERVED')), 0) as approved_unpaid,
                   coalesce(sum(amount - reversed_amount) filter (where status = 'PAID'), 0) as paid from affiliate_commissions"""));
        out.put("payouts", AdminSql.one(jdbc, """
            select coalesce(sum(amount) filter (where status in ('REQUESTED','APPROVED','PROCESSING')), 0) as outstanding,
                   count(*) filter (where status in ('REQUESTED','APPROVED','PROCESSING')) as outstanding_count,
                   coalesce(sum(amount) filter (where status = 'PAID'), 0) as paid_out from affiliate_withdrawals"""));
        return out;
    }

    @GetMapping
    public Map<String, Object> directory(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "created") String sort, @RequestParam(defaultValue = "desc") String dir,
                                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        AdminSql.Where w = new AdminSql.Where();
        if (q != null && !q.isBlank()) { String l = AdminSql.like(q); w.and("(lower(a.full_name) like ? escape '\\' or lower(a.email) like ? escape '\\' or lower(coalesce(a.code,'')) like ? escape '\\' or a.id::text like ? escape '\\' or a.phone like ? escape '\\')", l, l, l, l, l); }
        w.andIf(status, "a.status = ?", status == null ? null : status.toUpperCase());
        String cols = """
            a.id, a.full_name, a.email, a.phone, a.code, a.status, a.country, a.created_at, a.approved_at,
            (select count(*) from affiliate_referrals r where r.affiliate_id = a.id) as total_referrals,
            (select count(*) from affiliate_referrals r where r.affiliate_id = a.id and r.first_paid_at is not null) as conversions,
            (select coalesce(sum(c.amount - c.reversed_amount) filter (where c.kind <> 'CLAWBACK' and c.status <> 'CANCELLED'), 0) from affiliate_commissions c where c.affiliate_id = a.id) as commission_earned,
            (select coalesce(sum(c.amount - c.reversed_amount) filter (where c.status = 'PENDING'), 0) from affiliate_commissions c where c.affiliate_id = a.id) as pending_commission,
            (select coalesce(sum(wd.amount) filter (where wd.status = 'PAID'), 0) from affiliate_withdrawals wd where wd.affiliate_id = a.id) as total_payouts,
            greatest((select max(k.clicked_at) from affiliate_clicks k where k.affiliate_id = a.id), (select max(r.attributed_at) from affiliate_referrals r where r.affiliate_id = a.id), (select max(c.created_at) from affiliate_commissions c where c.affiliate_id = a.id)) as last_activity""";
        return AdminSql.page(jdbc, cols, "affiliates a", w, AdminSql.order(SORT, sort, dir), page, size);
    }

    /** Referred businesses: the businesses owned by users this partner brought in, with their subscription. */
    @GetMapping("/{id}/referred-businesses")
    public List<Map<String, Object>> referred(@PathVariable UUID id) {
        return AdminSql.rows(jdbc, """
            select r.id as referral_id, r.attributed_at, r.attribution_source, r.first_paid_at, r.eligible_until, u.full_name as user_name, u.email as user_email,
                   b.id as business_id, b.name as business_name, b.status as business_status, s.plan_code, s.status as subscription_status
            from affiliate_referrals r join users u on u.id = r.referred_user_id
            left join memberships m on m.user_id = u.id and m.role = 'OWNER'
            left join businesses b on b.id = m.business_id left join subscriptions s on s.business_id = b.id
            where r.affiliate_id = ? order by r.attributed_at desc limit 500""", id);
    }

    @PostMapping
    @Transactional
    public AffiliateService.Dto create(@Valid @RequestBody AffiliateService.AdminCreate r) {
        AffiliateService.Dto d = affiliates.adminCreate(r);
        audit.ok("PARTNER_CREATED", "partner", d.id(), null, null, Map.of("code", String.valueOf(d.code())));
        return d;
    }

    @PutMapping("/{id}")
    @Transactional
    public AffiliateService.Dto edit(@PathVariable UUID id, @Valid @RequestBody AffiliateService.AdminEdit r) {
        AffiliateService.Dto d = affiliates.adminEdit(id, r);
        audit.ok("PARTNER_EDITED", "partner", id, null, null, null);
        return d;
    }

    /** Only a partner with no referral, click, commission or payout history can be removed; otherwise this answers 409 and the partner is suspended instead. */
    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable UUID id) {
        affiliates.adminDelete(id);
        audit.ok("PARTNER_DELETED", "partner", id, null, "no referral or payment history", null);
        return Map.of("ok", true);
    }
}
