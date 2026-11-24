package com.amsati.shopscale.superadmin;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Platform-wide figures computed from the live tables. Collected revenue and expected recurring revenue are kept apart on purpose. */
@RestController
@RequestMapping("/api/super-admin/dashboard")
@PreAuthorize("@superAdmin.ok()")
public class SuperAdminDashboardController {
    private final JdbcTemplate jdbc;

    public SuperAdminDashboardController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businesses", AdminSql.one(jdbc, """
            select count(*) as total,
                   count(*) filter (where b.status = 'ACTIVE') as active,
                   count(*) filter (where b.status = 'SUSPENDED') as suspended,
                   count(*) filter (where s.status = 'TRIALING') as trial,
                   count(*) filter (where s.status in ('ACTIVE','PAST_DUE','GRACE_PERIOD') and s.plan_code <> 'FREE') as paid,
                   count(*) filter (where s.status = 'EXPIRED') as expired_subscription,
                   count(*) filter (where s.status = 'CANCELLED') as cancelled_subscription,
                   count(*) filter (where s.id is null) as without_subscription
            from businesses b left join subscriptions s on s.business_id = b.id"""));
        out.put("users", AdminSql.one(jdbc, """
            select count(*) as total, count(*) filter (where active) as active_accounts,
                   (select count(distinct user_id) from memberships where last_active_at >= now() - interval '30 days') as seen_last_30_days
            from users where platform_role is null"""));
        out.put("partners", AdminSql.one(jdbc, """
            select count(*) as total, count(*) filter (where status = 'APPROVED') as active, count(*) filter (where status = 'SUSPENDED') as inactive,
                   count(*) filter (where status = 'PENDING') as pending, count(*) filter (where status = 'REJECTED') as rejected,
                   (select count(*) from affiliate_referrals) as referrals,
                   (select count(*) from affiliate_referrals where first_paid_at is not null) as conversions
            from affiliates"""));
        out.put("subscriptionsByPlan", AdminSql.rows(jdbc, "select plan_code, status, count(*) as count from subscriptions group by plan_code, status order by plan_code, status"));
        // Expected recurring revenue: the price each subscriber actually pays (their agreed price, else the plan's list price) for every currently paying subscription (annual plans spread over 12 months).
        // It is an expectation from plan prices, not money received.
        out.put("recurringRevenue", AdminSql.one(jdbc, """
            select coalesce(sum(case when s.locked_price is not null and s.locked_plan_code = s.plan_code and s.locked_cycle = s.billing_cycle
                                     then (case when s.billing_cycle = 'ANNUAL' then s.locked_price / 12 else s.locked_price end)
                                     when s.billing_cycle = 'ANNUAL' then p.annual_price / 12 else p.monthly_price end), 0) as expected_mrr,
                   count(*) as paying_subscriptions
            from subscriptions s join plans p on p.code = s.plan_code
            where s.status in ('ACTIVE','PAST_DUE','GRACE_PERIOD') and s.plan_code <> 'FREE'"""));
        out.put("collectedRevenue", AdminSql.one(jdbc, """
            select coalesce(sum(amount) filter (where paid_at >= date_trunc('month', now())), 0) as this_month,
                   coalesce(sum(amount) filter (where paid_at >= now() - interval '30 days'), 0) as last_30_days,
                   coalesce(sum(amount), 0) as all_time, count(*) as payments
            from subscription_payments where status = 'SUCCESS'"""));
        out.put("registrations", AdminSql.rows(jdbc, """
            select to_char(m, 'YYYY-MM') as month, (select count(*) from businesses b where date_trunc('month', b.created_at) = m) as businesses,
                   (select count(*) from subscriptions s where date_trunc('month', s.created_at) = m) as subscriptions
            from generate_series(date_trunc('month', now()) - interval '11 months', date_trunc('month', now()), interval '1 month') as m order by m"""));
        out.put("recentActivity", AdminSql.rows(jdbc, "select action, target_type, target_id, actor_email, success, reason, created_at from admin_audit_log order by created_at desc limit 15"));
        out.put("recentBusinesses", AdminSql.rows(jdbc, "select id, name, business_type, status, created_at from businesses order by created_at desc limit 8"));
        out.put("openSupport", AdminSql.one(jdbc, "select count(*) filter (where status in ('OPEN','IN_PROGRESS','WAITING_FOR_CUSTOMER')) as open, count(*) filter (where status = 'OPEN' and priority in ('HIGH','URGENT')) as urgent from support_tickets"));
        return out;
    }
}
