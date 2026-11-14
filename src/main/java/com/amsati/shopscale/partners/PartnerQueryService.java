package com.amsati.shopscale.partners;

import com.amsati.shopscale.common.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read models for the partner dashboard, the administration screens, reports and CSV exports. Read-only. */
@Service
@RequiredArgsConstructor
public class PartnerQueryService {
    private final JdbcTemplate jdbc;
    private final AffiliateService affiliates;
    private final CommissionService commissions;
    private final PartnerSettingsService settings;

    private static String camel(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return b.toString();
    }

    List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            var md = rs.getMetaData();
            for (int c = 1; c <= md.getColumnCount(); c++) {
                Object v = rs.getObject(c);
                if (v instanceof Timestamp t) v = t.toInstant();
                else if (v instanceof java.sql.Date d) v = d.toLocalDate();
                m.put(camel(md.getColumnLabel(c)), v);
            }
            return m;
        }, args);
    }

    private static int cap(int limit, int max) { return Math.min(Math.max(limit, 1), max); }

    static String maskName(String full) {
        if (full == null || full.isBlank()) return "Customer";
        String[] p = full.trim().split("\\s+");
        return p.length == 1 ? p[0] : p[0] + " " + p[p.length - 1].charAt(0) + ".";
    }

    // ---------- the partner's own numbers ----------
    public Map<String, Object> stats(UUID aid) {
        Map<String, Object> out = new LinkedHashMap<>();
        Long clicks = jdbc.queryForObject("select count(*) from affiliate_clicks where affiliate_id = ?", Long.class, aid);
        Map<String, Object> ref = rows("select count(*) as registrations, count(first_paid_at) as paying from affiliate_referrals where affiliate_id = ?", aid).get(0);
        long registrations = ((Number) ref.get("registrations")).longValue(), paying = ((Number) ref.get("paying")).longValue();
        Map<String, Object> m = rows("select "
            + "coalesce(sum(amount - reversed_amount) filter (where kind <> 'CLAWBACK' and status <> 'CANCELLED'), 0) as earned, "
            + "coalesce(sum(amount - reversed_amount) filter (where status = 'PENDING'), 0) as pending, "
            + "coalesce(sum(amount - reversed_amount) filter (where status = 'AVAILABLE'), 0) as available_net, "
            + "coalesce(sum(amount - reversed_amount) filter (where status = 'RESERVED'), 0) as reserved, "
            + "coalesce(sum(amount - reversed_amount) filter (where status = 'PAID'), 0) as paid, "
            + "coalesce(sum(reversed_amount), 0) as reversed, "
            + "coalesce(min(available_at) filter (where status = 'PENDING'), null) as next_available_at "
            + "from affiliate_commissions where affiliate_id = ?", aid).get(0);
        BigDecimal net = (BigDecimal) m.get("availableNet");
        out.put("clicks", clicks == null ? 0 : clicks);
        out.put("registrations", registrations);
        out.put("payingCustomers", paying);
        out.put("signupRate", clicks == null || clicks == 0 ? 0 : round(registrations * 100.0 / clicks));
        out.put("conversionRate", clicks == null || clicks == 0 ? 0 : round(paying * 100.0 / clicks));
        out.put("totalEarned", m.get("earned"));
        out.put("pending", m.get("pending"));
        out.put("available", net.max(BigDecimal.ZERO));
        out.put("outstandingDeductions", net.signum() < 0 ? net.negate() : BigDecimal.ZERO);
        out.put("reserved", m.get("reserved"));
        out.put("paid", m.get("paid"));
        out.put("reversed", m.get("reversed"));
        out.put("nextAvailableAt", m.get("nextAvailableAt"));
        return out;
    }

    private static double round(double v) { return Math.round(v * 10.0) / 10.0; }

    public Map<String, Object> dashboard() {
        UUID aid = affiliates.requireExisting();
        commissions.releaseDueQuietly(aid);
        Programme p = settings.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partner", affiliates.mine());
        out.put("stats", stats(aid));
        out.put("programme", settings.publicView());
        out.put("bank", affiliates.bank());
        out.put("canWithdraw", ((BigDecimal) stats(aid).get("available")).compareTo(p.minWithdrawal()) >= 0);
        return out;
    }

    public List<Map<String, Object>> referrals(UUID aid, int limit) {
        List<Map<String, Object>> list = rows("select r.id, r.attributed_at, r.attribution_source, r.first_paid_at, r.eligible_until, u.full_name, "
            + "(select s.plan_code from subscriptions s join memberships m on m.business_id = s.business_id and m.role = 'OWNER' where m.user_id = r.referred_user_id order by s.updated_at desc limit 1) as plan_code, "
            + "(select p.status from subscription_payments p join memberships m on m.business_id = p.business_id and m.role = 'OWNER' where m.user_id = r.referred_user_id order by p.created_at desc limit 1) as payment_status, "
            + "(select c.status from affiliate_commissions c where c.referral_id = r.id and c.kind = 'EARNED' and c.amount > 0 order by c.earned_at desc limit 1) as commission_status "
            + "from affiliate_referrals r join users u on u.id = r.referred_user_id where r.affiliate_id = ? order by r.attributed_at desc limit ?", aid, cap(limit, 500));
        Instant now = Instant.now();
        for (Map<String, Object> m : list) {
            m.put("customer", maskName((String) m.remove("fullName")));
            Instant paid = (Instant) m.get("firstPaidAt"), until = (Instant) m.get("eligibleUntil");
            m.put("customerStatus", paid == null ? "REGISTERED" : until != null && until.isAfter(now) ? "EARNING" : "PERIOD_ENDED");
        }
        return list;
    }

    public List<Map<String, Object>> myReferrals(int limit) { return referrals(affiliates.requireExisting(), limit); }

    private static final String COMMISSION_SQL = "select c.id, c.affiliate_id, c.kind, c.status, c.payment_amount, c.rate, c.eligible_fraction, c.amount, c.reversed_amount, (c.amount - c.reversed_amount) as net_amount, "
        + "c.earned_at, c.available_at, c.withdrawal_id, c.reason, p.plan_code, p.billing_cycle, p.paid_at, p.reference as payment_reference, "
        + "(select e.reason from affiliate_commission_events e where e.commission_id = c.id and e.event in ('REVERSED','PARTIAL_REVERSAL') order by e.created_at desc limit 1) as reversal_reason "
        + "from affiliate_commissions c left join subscription_payments p on p.id = c.payment_id";

    public List<Map<String, Object>> commissions(UUID aid, int limit) {
        return rows(COMMISSION_SQL + " where c.affiliate_id = ? and not (c.status = 'CANCELLED' and c.amount = 0) order by c.earned_at desc limit ?", aid, cap(limit, 500));
    }

    public List<Map<String, Object>> myCommissions(int limit) { return commissions(affiliates.requireExisting(), limit); }

    // ---------- administration ----------
    /** Ledger view with filters on partner, customer, payment, status and date. */
    public List<Map<String, Object>> ledger(UUID affiliateId, String status, String customer, String paymentRef, LocalDate from, LocalDate to, int limit) {
        StringBuilder sql = new StringBuilder("select c.id, c.affiliate_id, a.full_name as affiliate_name, a.code as affiliate_code, c.kind, c.status, c.payment_amount, c.rate, c.eligible_fraction, c.amount, c.reversed_amount, (c.amount - c.reversed_amount) as net_amount, "
            + "c.earned_at, c.available_at, c.withdrawal_id, c.reason, p.plan_code, p.billing_cycle, p.paid_at, p.reference as payment_reference, u.full_name as customer_name, u.email as customer_email "
            + "from affiliate_commissions c join affiliates a on a.id = c.affiliate_id left join subscription_payments p on p.id = c.payment_id "
            + "left join affiliate_referrals r on r.id = c.referral_id left join users u on u.id = r.referred_user_id where 1=1");
        List<Object> args = new ArrayList<>();
        if (affiliateId != null) { sql.append(" and c.affiliate_id = ?"); args.add(affiliateId); }
        if (status != null && !status.isBlank()) { sql.append(" and c.status = ?"); args.add(status.toUpperCase(Locale.ROOT)); }
        if (customer != null && !customer.isBlank()) { sql.append(" and (lower(u.email) like ? escape '\\' or lower(u.full_name) like ? escape '\\')"); String l = Db.like(customer); args.add(l); args.add(l); }
        if (paymentRef != null && !paymentRef.isBlank()) { sql.append(" and lower(p.reference) like ? escape '\\'"); args.add(Db.like(paymentRef)); }
        if (from != null) { sql.append(" and c.earned_at >= ?"); args.add(Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC))); }
        if (to != null) { sql.append(" and c.earned_at < ?"); args.add(Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))); }
        sql.append(" order by c.earned_at desc limit ?");
        args.add(cap(limit, 2000));
        return rows(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> adminReferrals(UUID affiliateId, int limit) {
        String where = affiliateId == null ? "" : " where r.affiliate_id = ?";
        Object[] args = affiliateId == null ? new Object[]{cap(limit, 1000)} : new Object[]{affiliateId, cap(limit, 1000)};
        return rows("select r.id, r.affiliate_id, a.full_name as affiliate_name, a.code as affiliate_code, u.full_name as customer_name, u.email as customer_email, r.attributed_at, r.attribution_source, r.first_paid_at, r.eligible_until, r.rate, r.commission_months, r.holding_days "
            + "from affiliate_referrals r join affiliates a on a.id = r.affiliate_id join users u on u.id = r.referred_user_id" + where + " order by r.attributed_at desc limit ?", args);
    }

    public Map<String, Object> affiliateDetail(UUID id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partner", affiliates.get(id));
        out.put("stats", stats(id));
        out.put("referrals", adminReferrals(id, 200));
        out.put("commissions", ledger(id, null, null, null, null, null, 200));
        out.put("audit", rows("select action, detail, actor_user_id, created_at from affiliate_audit_log where entity = 'affiliate' and entity_id = ? order by created_at desc limit 100", id));
        return out;
    }

    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(rows("select count(*) as total_affiliates, count(*) filter (where status = 'APPROVED') as active_affiliates, count(*) filter (where status = 'PENDING') as pending_applications, count(*) filter (where status = 'SUSPENDED') as suspended_affiliates from affiliates").get(0));
        long clicks = jdbc.queryForObject("select count(*) from affiliate_clicks", Long.class);
        Map<String, Object> r = rows("select count(*) as registrations, count(first_paid_at) as paying_customers from affiliate_referrals").get(0);
        out.put("clicks", clicks);
        out.putAll(r);
        long reg = ((Number) r.get("registrations")).longValue(), pay = ((Number) r.get("payingCustomers")).longValue();
        out.put("signupRate", clicks == 0 ? 0 : round(reg * 100.0 / clicks));
        out.put("conversionRate", clicks == 0 ? 0 : round(pay * 100.0 / clicks));
        out.putAll(rows("select coalesce(sum(payment_amount * (1 - case when amount = 0 then 1 else reversed_amount / amount end)) filter (where kind = 'EARNED' and status <> 'CANCELLED'), 0) as attributed_revenue, "
            + "coalesce(sum(amount - reversed_amount) filter (where status = 'PENDING'), 0) as liability_pending, "
            + "coalesce(sum(amount - reversed_amount) filter (where status = 'AVAILABLE'), 0) as liability_available, "
            + "coalesce(sum(amount - reversed_amount) filter (where status = 'RESERVED'), 0) as liability_reserved, "
            + "coalesce(sum(amount - reversed_amount) filter (where status = 'PAID'), 0) as total_paid, "
            + "coalesce(sum(reversed_amount), 0) as total_reversed from affiliate_commissions").get(0));
        out.putAll(rows("select count(*) as open_withdrawals, coalesce(sum(amount), 0) as open_withdrawal_amount from affiliate_withdrawals where status in ('REQUESTED','APPROVED','PROCESSING')").get(0));
        out.put("programme", settings.get());
        return out;
    }

    public List<Map<String, Object>> performance(int limit) {
        return rows("select a.id, a.full_name, a.email, a.code, a.status, "
            + "(select count(*) from affiliate_clicks k where k.affiliate_id = a.id) as clicks, "
            + "(select count(*) from affiliate_referrals r where r.affiliate_id = a.id) as registrations, "
            + "(select count(*) from affiliate_referrals r where r.affiliate_id = a.id and r.first_paid_at is not null) as paying_customers, "
            + "coalesce((select sum(c.payment_amount) from affiliate_commissions c where c.affiliate_id = a.id and c.kind = 'EARNED' and c.status <> 'CANCELLED' and c.reversed_amount < c.amount), 0) as attributed_revenue, "
            + "coalesce((select sum(c.amount - c.reversed_amount) from affiliate_commissions c where c.affiliate_id = a.id and c.kind <> 'CLAWBACK' and c.status <> 'CANCELLED'), 0) as earned, "
            + "coalesce((select sum(c.amount - c.reversed_amount) from affiliate_commissions c where c.affiliate_id = a.id and c.status = 'PAID'), 0) as paid "
            + "from affiliates a where a.status in ('APPROVED','SUSPENDED') order by earned desc, a.created_at limit ?", cap(limit, 500));
    }

    public List<Map<String, Object>> audit(int limit) {
        return rows("select l.action, l.entity, l.entity_id, l.detail, l.created_at, u.email as actor_email from affiliate_audit_log l left join users u on u.id = l.actor_user_id order by l.created_at desc limit ?", cap(limit, 500));
    }

    // ---------- CSV ----------
    static String csvCell(Object v) {
        if (v == null) return "";
        String s = v.toString();
        // A spreadsheet runs text that starts with = + - @ as a formula; neutralise it.
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number)) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    static String csv(List<Map<String, Object>> rows, List<String> columns) {
        StringBuilder b = new StringBuilder(String.join(",", columns)).append("\r\n");
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>();
            for (String c : columns) cells.add(csvCell(r.get(c)));
            b.append(String.join(",", cells)).append("\r\n");
        }
        return b.toString();
    }

    public String export(String kind) {
        switch (kind) {
            case "withdrawals":
                return csv(rows("select w.id, a.full_name as partner, a.email, w.amount, w.currency, w.status, w.bank_holder_name, w.bank_name, ('******' || w.account_last4) as account, w.requested_at, w.approved_at, w.processing_at, w.paid_at, w.transfer_reference, w.closed_reason "
                    + "from affiliate_withdrawals w join affiliates a on a.id = w.affiliate_id order by w.requested_at desc limit 10000"),
                    List.of("id", "partner", "email", "amount", "currency", "status", "bankHolderName", "bankName", "account", "requestedAt", "approvedAt", "processingAt", "paidAt", "transferReference", "closedReason"));
            case "commissions":
                return csv(ledger(null, null, null, null, null, null, 2000),
                    List.of("id", "affiliateName", "affiliateCode", "kind", "status", "customerName", "paymentReference", "planCode", "paymentAmount", "rate", "amount", "reversedAmount", "netAmount", "earnedAt", "availableAt", "reason"));
            case "affiliates":
                return csv(performance(500), List.of("id", "fullName", "email", "code", "status", "clicks", "registrations", "payingCustomers", "attributedRevenue", "earned", "paid"));
            case "referrals":
                return csv(adminReferrals(null, 1000), List.of("id", "affiliateName", "affiliateCode", "customerName", "attributedAt", "attributionSource", "firstPaidAt", "eligibleUntil", "rate", "commissionMonths", "holdingDays"));
            default:
                throw ApiException.notFound("Unknown export");
        }
    }
}
