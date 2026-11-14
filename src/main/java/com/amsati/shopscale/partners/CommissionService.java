package com.amsati.shopscale.partners;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.Tenant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The commission ledger.
 *
 * Money rules enforced here and by database constraints:
 *  - A commission exists only for a subscription payment that ShopScale itself verified as SUCCESS; its amount comes from that row,
 *    never from a request. One commission per payment (unique payment_id), so webhooks, retries and the reconcile sweep can all run it again safely.
 *  - Rows change status only here; every change is also appended to affiliate_commission_events (a table that refuses edits and deletes).
 *  - A balance is never stored. It is the sum of the rows, so there is nothing to edit.
 *  - Refunds and chargebacks reverse in proportion. A reversal that arrives after the money was reserved for, or sent in, a payout becomes a
 *    negative CLAWBACK row that is netted from the partner's next withdrawal, so a balance cannot silently go wrong.
 */
@Service
@RequiredArgsConstructor
public class CommissionService {
    private static final Logger log = LoggerFactory.getLogger(CommissionService.class);

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txm;
    private final PartnerSettingsService settings;
    private final PartnerAudit audit;
    private final PartnerMail mail;

    public record AdjustRequest(@NotNull UUID affiliateId, @NotNull BigDecimal amount, @NotBlank @Size(min = 5, max = 500) String reason) {}
    public record ReverseRequest(@NotBlank @Size(min = 5, max = 500) String reason) {}
    public record RefundRequest(@NotBlank @Size(max = 80) String paymentReference, @NotBlank @Size(min = 3, max = 100) String refundReference, @NotNull BigDecimal amount) {}

    // ---------- generating ----------
    /**
     * Called after a subscription payment has been settled. Waits for the payment's own transaction to commit, then does its work in a new
     * transaction. Never throws: a problem here is logged and picked up again by {@link #reconcile()}; it can never fail a customer's payment.
     */
    public void afterPaymentSettled(UUID paymentId) {
        Runnable work = () -> {
            try {
                new TransactionTemplate(txm, requiresNew()).executeWithoutResult(st -> record(paymentId));
            } catch (Exception e) {
                log.error("Could not record affiliate commission for payment {} (it will be retried by the reconcile job): {}", paymentId, e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { work.run(); }
            });
        } else work.run();
    }

    private static TransactionDefinition requiresNew() {
        DefaultTransactionDefinition d = new DefaultTransactionDefinition();
        d.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return d;
    }

    record Ref(UUID id, UUID affiliateId, BigDecimal rate, int months, int holdingDays, Instant firstPaidAt, Instant eligibleUntil, String affiliateStatus, String email, String name) {}

    /** Creates the commission (or a "no commission" marker) for one payment. Idempotent. */
    void record(UUID paymentId) {
        var pays = jdbc.query("select business_id, amount, currency, status, paid_at, period_start, period_end from subscription_payments where id = ?",
            (rs, i) -> new Object[]{Db.uuid(rs, "business_id"), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"), Db.inst(rs, "paid_at"), Db.inst(rs, "period_start"), Db.inst(rs, "period_end")}, paymentId);
        if (pays.isEmpty()) return;
        Object[] p = pays.get(0);
        BigDecimal amount = (BigDecimal) p[1];
        Instant paidAt = (Instant) p[4];
        if (!"SUCCESS".equals(p[3]) || paidAt == null || amount == null || amount.signum() <= 0 || !"NGN".equalsIgnoreCase((String) p[2])) return;

        // Serialise everything about one referred customer: two payments of the same customer cannot both decide "first payment".
        List<Ref> refs = jdbc.query("select r.id, r.affiliate_id, r.rate, r.commission_months, r.holding_days, r.first_paid_at, r.eligible_until, a.status, a.email, a.full_name "
            + "from affiliate_referrals r join affiliates a on a.id = r.affiliate_id join memberships m on m.user_id = r.referred_user_id "
            + "where m.business_id = ? and m.role = 'OWNER' order by m.created_at limit 1 for update of r",
            (rs, i) -> new Ref(Db.uuid(rs, "id"), Db.uuid(rs, "affiliate_id"), rs.getBigDecimal("rate"), rs.getInt("commission_months"), rs.getInt("holding_days"),
                Db.inst(rs, "first_paid_at"), Db.inst(rs, "eligible_until"), rs.getString(8), rs.getString("email"), rs.getString("full_name")), p[0]);
        if (refs.isEmpty()) return;
        Ref r = refs.get(0);
        Integer existing = jdbc.queryForObject("select count(*) from affiliate_commissions where payment_id = ?", Integer.class, paymentId);
        if (existing != null && existing > 0) return;
        if (!"APPROVED".equals(r.affiliateStatus()) && !"SUSPENDED".equals(r.affiliateStatus())) return;

        Instant windowStart = r.firstPaidAt() != null ? r.firstPaidAt() : paidAt;
        Instant windowEnd = r.eligibleUntil() != null ? r.eligibleUntil() : CommissionMath.windowEnd(paidAt, r.months());
        BigDecimal fraction = CommissionMath.eligibleFraction(paidAt, (Instant) p[5], (Instant) p[6], windowStart, windowEnd);
        BigDecimal commission = CommissionMath.commission(amount, r.rate(), fraction);
        UUID id = UUID.randomUUID();
        if (commission.signum() <= 0) {
            int n = jdbc.update("insert into affiliate_commissions (id, affiliate_id, referral_id, payment_id, kind, status, payment_amount, rate, eligible_fraction, amount, earned_at, available_at, reason) "
                + "values (?,?,?,?,'EARNED','CANCELLED',?,?,?,0,?,?,?) on conflict (payment_id) do nothing",
                id, r.affiliateId(), r.id(), paymentId, amount, r.rate(), fraction, Db.ts(paidAt), Db.ts(paidAt), "No commission: this payment is outside the earning period");
            if (n == 1) event(id, r.affiliateId(), "NO_COMMISSION", null, "CANCELLED", BigDecimal.ZERO, null, null, "Outside the earning period");
            return;
        }
        if (r.firstPaidAt() == null) jdbc.update("update affiliate_referrals set first_paid_at = ?, eligible_until = ? where id = ? and first_paid_at is null", Db.ts(paidAt), Db.ts(windowEnd), r.id());
        int n = jdbc.update("insert into affiliate_commissions (id, affiliate_id, referral_id, payment_id, kind, status, payment_amount, rate, eligible_fraction, amount, earned_at, available_at) "
            + "values (?,?,?,?,'EARNED','PENDING',?,?,?,?,?,?) on conflict (payment_id) do nothing",
            id, r.affiliateId(), r.id(), paymentId, amount, r.rate(), fraction, commission, Db.ts(paidAt), Db.ts(CommissionMath.availableAt(paidAt, r.holdingDays())));
        if (n == 1) {
            event(id, r.affiliateId(), "EARNED", null, "PENDING", commission, null, null, null);
            mail.commissionEarned(r.email(), r.name(), commission, amount, r.holdingDays());
        }
    }

    void event(UUID commissionId, UUID affiliateId, String event, String from, String to, BigDecimal amount, UUID withdrawalId, UUID actor, String reason) {
        jdbc.update("insert into affiliate_commission_events (id, commission_id, affiliate_id, event, from_status, to_status, amount, withdrawal_id, actor_user_id, reason) values (?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), commissionId, affiliateId, event, from, to, amount, withdrawalId, actor, reason == null ? null : reason.substring(0, Math.min(500, reason.length())));
    }

    // ---------- holding period ----------
    /** Moves commissions whose holding period has passed from PENDING to AVAILABLE (only for approved partners). Optionally for one partner. */
    @Transactional
    public int releaseDue(UUID affiliateId) {
        String sql = "with due as (select c.id from affiliate_commissions c join affiliates a on a.id = c.affiliate_id "
            + "where c.status = 'PENDING' and c.available_at <= now() and a.status = 'APPROVED' " + (affiliateId == null ? "" : "and c.affiliate_id = ? ")
            + "for update of c skip locked) update affiliate_commissions c set status = 'AVAILABLE', updated_at = now() from due where c.id = due.id returning c.id, c.affiliate_id, c.amount - c.reversed_amount";
        List<Object[]> rows = affiliateId == null
            ? jdbc.query(sql, (rs, i) -> new Object[]{Db.uuid(rs, String.valueOf(1)), Db.uuid(rs, String.valueOf(2)), rs.getBigDecimal(3)})
            : jdbc.query(sql, (rs, i) -> new Object[]{Db.uuid(rs, String.valueOf(1)), Db.uuid(rs, String.valueOf(2)), rs.getBigDecimal(3)}, affiliateId);
        Map<UUID, BigDecimal> totals = new LinkedHashMap<>();
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Object[] r : rows) {
            event((UUID) r[0], (UUID) r[1], "AVAILABLE", "PENDING", "AVAILABLE", (BigDecimal) r[2], null, null, "Holding period finished");
            totals.merge((UUID) r[1], (BigDecimal) r[2], BigDecimal::add);
            counts.merge((UUID) r[1], 1, Integer::sum);
        }
        totals.forEach((aff, total) -> jdbc.query("select email, full_name from affiliates where id = ?", (rs, i) -> { mail.commissionsAvailable(rs.getString(1), rs.getString(2), total, counts.get(aff)); return 0; }, aff));
        return rows.size();
    }

    /** Same as releaseDue for one partner but never throws (used when a partner opens their dashboard). */
    public void releaseDueQuietly(UUID affiliateId) {
        try { new TransactionTemplate(txm).executeWithoutResult(st -> releaseDue(affiliateId)); }
        catch (Exception e) { log.warn("Could not release due commissions for {}: {}", affiliateId, e.toString()); }
    }

    // ---------- refunds, chargebacks, administrative reversal ----------
    /**
     * A refund or chargeback of (part of) a subscription payment. {@code reference} identifies the refund so a repeated notice changes nothing.
     */
    @Transactional
    public void applyRefund(UUID paymentId, String reference, String source, BigDecimal refunded) {
        if (refunded == null || refunded.signum() <= 0) return;
        BigDecimal paymentAmount = jdbc.query("select amount from subscription_payments where id = ?", (rs, i) -> rs.getBigDecimal(1), paymentId).stream().findFirst().orElse(null);
        if (paymentAmount == null) return;
        BigDecimal capped = refunded.min(paymentAmount);
        int n = jdbc.update("insert into affiliate_refund_events (id, payment_id, reference, source, amount) values (?,?,?,?,?) on conflict (reference) do nothing", UUID.randomUUID(), paymentId, reference, source, capped);
        if (n == 0) return;
        BigDecimal total = jdbc.queryForObject("select coalesce(sum(amount), 0) from affiliate_refund_events where payment_id = ?", BigDecimal.class, paymentId);
        List<Row> rows = jdbc.query(ROW_SQL + " where payment_id = ? for update", (rs, i) -> row(rs), paymentId);
        if (rows.isEmpty()) return;   // no partner on this payment
        Row c = rows.get(0);
        BigDecimal delta = CommissionMath.reversalTarget(c.amount(), paymentAmount, total).subtract(c.reversed());
        if (delta.signum() > 0) reverse(c, delta, "Refund (" + source.toLowerCase() + ") " + reference, null);
    }

    @Transactional
    public void adminReverse(UUID commissionId, String reason) {
        List<Row> rows = jdbc.query(ROW_SQL + " where id = ? for update", (rs, i) -> row(rs), commissionId);
        if (rows.isEmpty()) throw ApiException.notFound("Commission not found");
        Row c = rows.get(0);
        if ("CLAWBACK".equals(c.kind())) throw ApiException.conflict("A deduction cannot be reversed here; add a credit adjustment instead");
        BigDecimal delta = c.amount().subtract(c.reversed());
        if (delta.signum() <= 0 || "REVERSED".equals(c.status()) || "CANCELLED".equals(c.status())) throw ApiException.conflict("This commission has already been reversed");
        reverse(c, delta, reason, Tenant.userId());
        audit.record("COMMISSION_REVERSED", "commission", commissionId, reason + " (" + delta + ")");
    }

    record Row(UUID id, UUID affiliateId, UUID referralId, String kind, String status, BigDecimal amount, BigDecimal reversed, UUID withdrawalId) {}

    private static final String ROW_SQL = "select id, affiliate_id, referral_id, kind, status, amount, reversed_amount, withdrawal_id from affiliate_commissions";

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(Db.uuid(rs, "id"), Db.uuid(rs, "affiliate_id"), Db.uuid(rs, "referral_id"), rs.getString("kind"), rs.getString("status"), rs.getBigDecimal("amount"), rs.getBigDecimal("reversed_amount"), Db.uuid(rs, "withdrawal_id"));
    }

    private void reverse(Row c, BigDecimal delta, String reason, UUID actor) {
        if ("REVERSED".equals(c.status()) || "CANCELLED".equals(c.status())) return;
        boolean full = c.reversed().add(delta).compareTo(c.amount()) >= 0;
        String newStatus = full ? "REVERSED" : c.status();
        String ev = full ? "REVERSED" : "PARTIAL_REVERSAL";
        boolean clawback = false;
        if ("RESERVED".equals(c.status())) {
            var w = jdbc.query("select status, amount from affiliate_withdrawals where id = ? for update", (rs, i) -> new Object[]{rs.getString(1), rs.getBigDecimal(2)}, c.withdrawalId());
            String ws = w.isEmpty() ? "" : (String) w.get(0)[0];
            if ("REQUESTED".equals(ws) || "APPROVED".equals(ws)) {
                // Not sent yet: the withdrawal simply gets smaller (or is cancelled when nothing is left).
                BigDecimal left = ((BigDecimal) w.get(0)[1]).subtract(delta);
                if (left.signum() <= 0) {
                    jdbc.update("update affiliate_withdrawals set status = 'CANCELLED', closed_reason = 'Every commission in this withdrawal was reversed', closed_at = now(), updated_at = now() where id = ?", c.withdrawalId());
                } else {
                    jdbc.update("update affiliate_withdrawals set amount = ?, updated_at = now() where id = ?", left, c.withdrawalId());
                }
            } else clawback = true;
        } else if ("PAID".equals(c.status())) clawback = true;
        jdbc.update("update affiliate_commissions set reversed_amount = reversed_amount + ?, status = ?, updated_at = now() where id = ?", delta, newStatus, c.id());
        event(c.id(), c.affiliateId(), ev, c.status(), newStatus, delta, c.withdrawalId(), actor, reason);
        if (clawback) {
            UUID cb = UUID.randomUUID();
            jdbc.update("insert into affiliate_commissions (id, affiliate_id, referral_id, kind, status, amount, earned_at, available_at, reason, created_by) values (?,?,?,'CLAWBACK','AVAILABLE',?,now(),now(),?,?)",
                cb, c.affiliateId(), c.referralId(), delta.negate(), "Deducted: " + reason, actor);
            event(cb, c.affiliateId(), "CLAWBACK", null, "AVAILABLE", delta.negate(), c.withdrawalId(), actor, "Reversal after the money was committed to a payout");
        }
        // A cancelled withdrawal frees what is still reserved under it.
        if (c.withdrawalId() != null) {
            String ws = jdbc.queryForObject("select status from affiliate_withdrawals where id = ?", String.class, c.withdrawalId());
            if ("CANCELLED".equals(ws)) releaseReservations(c.withdrawalId(), actor, "Withdrawal cancelled");
        }
    }

    /** Puts every commission still reserved for a withdrawal back to AVAILABLE. */
    void releaseReservations(UUID withdrawalId, UUID actor, String reason) {
        List<Object[]> rows = jdbc.query("update affiliate_commissions set status = 'AVAILABLE', withdrawal_id = null, updated_at = now() where withdrawal_id = ? and status = 'RESERVED' returning id, affiliate_id, amount - reversed_amount",
            (rs, i) -> new Object[]{Db.uuid(rs, String.valueOf(1)), Db.uuid(rs, String.valueOf(2)), rs.getBigDecimal(3)}, withdrawalId);
        for (Object[] r : rows) event((UUID) r[0], (UUID) r[1], "RELEASED", "RESERVED", "AVAILABLE", (BigDecimal) r[2], withdrawalId, actor, reason);
    }

    // ---------- administrative adjustments ----------
    @Transactional
    public UUID adjust(AdjustRequest r) {
        if (r.amount().signum() == 0) throw ApiException.badRequest("The adjustment amount cannot be zero");
        BigDecimal amt = r.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        if (amt.abs().compareTo(new BigDecimal("5000000")) > 0) throw ApiException.badRequest("That adjustment is larger than the allowed maximum");
        Integer exists = jdbc.queryForObject("select count(*) from affiliates where id = ?", Integer.class, r.affiliateId());
        if (exists == null || exists == 0) throw ApiException.notFound("Partner not found");
        UUID id = UUID.randomUUID();
        UUID actor = Tenant.userId();
        String kind = amt.signum() > 0 ? "ADJUSTMENT" : "CLAWBACK";
        jdbc.update("insert into affiliate_commissions (id, affiliate_id, kind, status, amount, earned_at, available_at, reason, created_by) values (?,?,?,'AVAILABLE',?,now(),now(),?,?)",
            id, r.affiliateId(), kind, amt, r.reason().trim(), actor);
        event(id, r.affiliateId(), "ADJUSTMENT", null, "AVAILABLE", amt, null, actor, r.reason().trim());
        audit.record("COMMISSION_ADJUSTED", "affiliate", r.affiliateId(), amt + ": " + r.reason().trim());
        return id;
    }

    /** Administrator records a refund made outside Paystack's notices (for example from the Paystack dashboard). */
    @Transactional
    public void recordRefund(RefundRequest r) {
        UUID pid = jdbc.query("select id from subscription_payments where reference = ?", (rs, i) -> Db.uuid(rs, String.valueOf(1)), r.paymentReference().trim()).stream().findFirst()
            .orElseThrow(() -> ApiException.notFound("No subscription payment has that reference"));
        applyRefund(pid, "admin:" + r.refundReference().trim(), "ADMIN", r.amount());
        audit.record("REFUND_RECORDED", "payment", pid, r.refundReference().trim() + " " + r.amount());
    }

    // ---------- safety net ----------
    /** Every payment that should have a commission but does not (a missed notification, a restart mid-payment) gets one. */
    public int reconcile() {
        List<UUID> missing = jdbc.query("select p.id from subscription_payments p join memberships m on m.business_id = p.business_id and m.role = 'OWNER' "
            + "join affiliate_referrals r on r.referred_user_id = m.user_id where p.status = 'SUCCESS' and p.amount > 0 and p.paid_at > now() - interval '90 days' "
            + "and not exists (select 1 from affiliate_commissions c where c.payment_id = p.id) order by p.paid_at limit 200", (rs, i) -> Db.uuid(rs, String.valueOf(1)));
        int done = 0;
        for (UUID id : missing) {
            try { new TransactionTemplate(txm, requiresNew()).executeWithoutResult(st -> record(id)); done++; }
            catch (Exception e) { log.error("Reconcile could not record commission for payment {}: {}", id, e.toString()); }
        }
        if (done > 0) log.info("Affiliate reconcile processed {} payment(s) that had no commission yet", done);
        return done;
    }

    @Scheduled(initialDelayString = "${app.partners.job-initial-delay-ms:120000}", fixedDelayString = "${app.partners.job-poll-ms:900000}")
    public void scheduled() {
        try { new TransactionTemplate(txm).executeWithoutResult(st -> releaseDue(null)); } catch (Exception e) { log.error("Releasing due commissions failed: {}", e.toString()); }
        try { reconcile(); } catch (Exception e) { log.error("Commission reconcile failed: {}", e.toString()); }
    }
}
