package com.amsati.shopscale.partners;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.SecretBox;
import com.amsati.shopscale.security.Tenant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdrawals: reserve, review, pay by manual bank transfer, record.
 *
 * - Requesting a withdrawal reserves ALL the partner's currently available commission rows in one transaction, with the partner row locked and
 *   a database rule allowing only one open withdrawal per partner. The reserved total is read back from the reserved rows themselves, so a
 *   withdrawal can never exceed what was available.
 * - Nothing becomes PAID by an approval click: PAID needs the bank transfer reference and the confirmed amount, and the reference can
 *   be used only once.
 * - Rejected, failed or cancelled withdrawals put the reserved commission back.
 * - An administrator cannot process a withdrawal that belongs to their own partner account.
 * Paying out through a provider later means adding a payout_method here; the ledger side does not change.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalService {
    private final JdbcTemplate jdbc;
    private final AffiliateService affiliates;
    private final CommissionService commissions;
    private final PartnerSettingsService settings;
    private final PartnerAudit audit;
    private final PartnerMail mail;
    private final SecretBox box;

    public record Dto(UUID id, UUID affiliateId, String affiliateName, String affiliateEmail, BigDecimal amount, String currency, String status, String payoutMethod,
                      String bankHolderName, String bankName, String accountNumberMasked, Instant requestedAt, Instant approvedAt, Instant processingAt, Instant paidAt,
                      String transferReference, String payoutNotes, String closedReason, int commissionCount) {}

    public record RejectRequest(@NotBlank @Size(min = 3, max = 500) String reason) {}
    public record PaidRequest(@NotBlank @Size(min = 4, max = 80) String transferReference, @NotNull BigDecimal amount, @Size(max = 500) String notes) {}

    private static final String SELECT = "select w.*, a.full_name, a.email, a.user_id as affiliate_user, "
        + "(select count(*) from affiliate_commissions c where c.withdrawal_id = w.id) as commission_count from affiliate_withdrawals w join affiliates a on a.id = w.affiliate_id";

    private static Dto map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Dto(Db.uuid(rs, "id"), Db.uuid(rs, "affiliate_id"), rs.getString("full_name"), rs.getString("email"), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"),
            rs.getString("payout_method"), rs.getString("bank_holder_name"), rs.getString("bank_name"), AffiliateService.mask(rs.getString("account_last4")), Db.inst(rs, "requested_at"),
            Db.inst(rs, "approved_at"), Db.inst(rs, "processing_at"), Db.inst(rs, "paid_at"), rs.getString("transfer_reference"), rs.getString("payout_notes"), rs.getString("closed_reason"), rs.getInt("commission_count"));
    }

    // ---------- the partner ----------
    public List<Dto> mine() {
        return jdbc.query(SELECT + " where w.affiliate_id = ? order by w.requested_at desc limit 200", (rs, i) -> map(rs), affiliates.requireExisting());
    }

    @Transactional
    public Dto request() {
        UUID aid = affiliates.requireApproved();
        jdbc.query("select id from affiliates where id = ? for update", (rs, i) -> 1, aid);   // one request at a time per partner
        commissions.releaseDue(aid);
        Programme prog = settings.get();
        var bank = jdbc.query("select holder_name, bank_name, bank_code, account_number_enc, account_last4 from affiliate_bank_accounts where affiliate_id = ?",
            (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}, aid);
        if (bank.isEmpty()) throw ApiException.badRequest("Add your bank details before requesting a withdrawal");
        BigDecimal available = jdbc.queryForObject("select coalesce(sum(amount - reversed_amount), 0) from affiliate_commissions where affiliate_id = ? and status = 'AVAILABLE'", BigDecimal.class, aid);
        if (available.compareTo(prog.minWithdrawal()) < 0)
            throw ApiException.badRequest("Your available balance is " + PartnerMail.naira(available.max(BigDecimal.ZERO)) + ". The minimum withdrawal is " + PartnerMail.naira(prog.minWithdrawal()) + ".");
        UUID wid = UUID.randomUUID();
        String[] b = bank.get(0);
        try {
            jdbc.update("insert into affiliate_withdrawals (id, affiliate_id, amount, bank_holder_name, bank_name, bank_code, account_number_enc, account_last4) values (?,?,?,?,?,?,?,?)",
                wid, aid, available, b[0], b[1], b[2], b[3], b[4]);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("You already have a withdrawal in progress. Wait for it to finish or cancel it.");
        }
        // Reserve exactly what is available right now; the total comes from the rows actually reserved.
        List<Object[]> rows = jdbc.query("update affiliate_commissions set status = 'RESERVED', withdrawal_id = ?, updated_at = now() where affiliate_id = ? and status = 'AVAILABLE' returning id, amount - reversed_amount",
            (rs, i) -> new Object[]{Db.uuid(rs, String.valueOf(1)), rs.getBigDecimal(2)}, wid, aid);
        BigDecimal reserved = BigDecimal.ZERO;
        for (Object[] r : rows) reserved = reserved.add((BigDecimal) r[1]);
        if (reserved.compareTo(prog.minWithdrawal()) < 0) throw ApiException.conflict("Your balance changed while the request was processed. Please try again.");
        jdbc.update("update affiliate_withdrawals set amount = ? where id = ?", reserved, wid);
        for (Object[] r : rows) commissions.event((UUID) r[0], aid, "RESERVED", "AVAILABLE", "RESERVED", (BigDecimal) r[1], wid, Tenant.userId(), "Withdrawal requested");
        Dto d = get(wid);
        mail.withdrawalRequested(d.affiliateEmail(), d.affiliateName(), d.amount(), d.affiliateName() + " (" + d.affiliateEmail() + ")");
        return d;
    }

    @Transactional
    public Dto cancel(UUID id) {
        UUID aid = affiliates.requireExisting();
        Dto d = lock(id);
        if (!d.affiliateId().equals(aid)) throw ApiException.notFound("Withdrawal not found");
        if (jdbc.update("update affiliate_withdrawals set status = 'CANCELLED', closed_reason = 'Cancelled by the partner', closed_by = ?, closed_at = now(), updated_at = now() where id = ? and status = 'REQUESTED'", Tenant.userId(), id) != 1)
            throw ApiException.conflict("Only a request that has not been approved yet can be cancelled");
        commissions.releaseReservations(id, Tenant.userId(), "Withdrawal cancelled by the partner");
        return get(id);
    }

    // ---------- administration ----------
    public Dto get(UUID id) {
        List<Dto> l = jdbc.query(SELECT + " where w.id = ?", (rs, i) -> map(rs), id);
        if (l.isEmpty()) throw ApiException.notFound("Withdrawal not found");
        return l.get(0);
    }

    private Dto lock(UUID id) {
        List<Dto> l = jdbc.query(SELECT + " where w.id = ? for update of w", (rs, i) -> map(rs), id);
        if (l.isEmpty()) throw ApiException.notFound("Withdrawal not found");
        return l.get(0);
    }

    public List<Dto> list(String status, UUID affiliateId, String q, int limit) {
        StringBuilder sql = new StringBuilder(SELECT + " where 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" and w.status = ?"); args.add(status.toUpperCase(Locale.ROOT)); }
        if (affiliateId != null) { sql.append(" and w.affiliate_id = ?"); args.add(affiliateId); }
        if (q != null && !q.isBlank()) { sql.append(" and (lower(a.full_name) like ? escape '\\' or lower(a.email) like ? escape '\\' or lower(coalesce(w.transfer_reference,'')) like ? escape '\\')"); String l = Db.like(q); args.add(l); args.add(l); args.add(l); }
        sql.append(" order by w.requested_at desc limit ?");
        args.add(Math.min(Math.max(limit, 1), 1000));
        return jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
    }

    /** Refuses when the administrator is the partner being paid. */
    private void notOwnRequest(UUID withdrawalId) {
        UUID owner = jdbc.queryForObject("select a.user_id from affiliate_withdrawals w join affiliates a on a.id = w.affiliate_id where w.id = ?", UUID.class, withdrawalId);
        if (Tenant.userId().equals(owner)) throw ApiException.forbidden("You cannot review your own withdrawal. Another administrator must do it.");
    }

    @Transactional
    public Dto approve(UUID id) {
        Dto d = lock(id);
        notOwnRequest(id);
        if (jdbc.update("update affiliate_withdrawals set status = 'APPROVED', approved_at = now(), approved_by = ?, updated_at = now() where id = ? and status = 'REQUESTED'", Tenant.userId(), id) != 1)
            throw ApiException.conflict("Only a requested withdrawal can be approved (it is " + d.status().toLowerCase(Locale.ROOT) + ")");
        audit.record("WITHDRAWAL_APPROVED", "withdrawal", id, PartnerMail.naira(d.amount()));
        mail.withdrawalApproved(d.affiliateEmail(), d.affiliateName(), d.amount());
        return get(id);
    }

    @Transactional
    public Dto reject(UUID id, String reason) {
        Dto d = lock(id);
        notOwnRequest(id);
        if (jdbc.update("update affiliate_withdrawals set status = 'REJECTED', closed_reason = ?, closed_by = ?, closed_at = now(), updated_at = now() where id = ? and status in ('REQUESTED','APPROVED')", reason.trim(), Tenant.userId(), id) != 1)
            throw ApiException.conflict("Only a requested or approved withdrawal can be rejected (it is " + d.status().toLowerCase(Locale.ROOT) + ")");
        commissions.releaseReservations(id, Tenant.userId(), "Withdrawal rejected");
        audit.record("WITHDRAWAL_REJECTED", "withdrawal", id, reason.trim());
        mail.withdrawalRejected(d.affiliateEmail(), d.affiliateName(), d.amount(), reason.trim());
        return get(id);
    }

    @Transactional
    public Dto markProcessing(UUID id) {
        Dto d = lock(id);
        notOwnRequest(id);
        if (jdbc.update("update affiliate_withdrawals set status = 'PROCESSING', processing_at = now(), processing_by = ?, updated_at = now() where id = ? and status = 'APPROVED'", Tenant.userId(), id) != 1)
            throw ApiException.conflict("Only an approved withdrawal can be marked as processing (it is " + d.status().toLowerCase(Locale.ROOT) + ")");
        audit.record("WITHDRAWAL_PROCESSING", "withdrawal", id, PartnerMail.naira(d.amount()));
        mail.withdrawalProcessing(d.affiliateEmail(), d.affiliateName(), d.amount());
        return get(id);
    }

    /** Only after the bank transfer has really been made: needs the bank's reference and the amount sent, which must equal the withdrawal. */
    @Transactional
    public Dto markPaid(UUID id, PaidRequest r) {
        Dto d = lock(id);
        notOwnRequest(id);
        if (r.amount().setScale(2, java.math.RoundingMode.HALF_UP).compareTo(d.amount()) != 0)
            throw ApiException.conflict("The amount you entered (" + PartnerMail.naira(r.amount()) + ") is not the withdrawal amount (" + PartnerMail.naira(d.amount()) + "). If the withdrawal changed, refresh and check the transfer.");
        try {
            if (jdbc.update("update affiliate_withdrawals set status = 'PAID', paid_at = now(), paid_by = ?, transfer_reference = ?, payout_notes = ?, updated_at = now() where id = ? and status = 'PROCESSING'",
                Tenant.userId(), r.transferReference().trim(), r.notes() == null || r.notes().isBlank() ? null : r.notes().trim(), id) != 1)
                throw ApiException.conflict("Only a withdrawal marked as processing can be recorded as paid (it is " + d.status().toLowerCase(Locale.ROOT) + ")");
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("That transfer reference was already used for another payout");
        }
        List<Object[]> rows = jdbc.query("update affiliate_commissions set status = 'PAID', updated_at = now() where withdrawal_id = ? and status = 'RESERVED' returning id, affiliate_id, amount - reversed_amount",
            (rs, i) -> new Object[]{Db.uuid(rs, String.valueOf(1)), Db.uuid(rs, String.valueOf(2)), rs.getBigDecimal(3)}, id);
        for (Object[] row : rows) commissions.event((UUID) row[0], (UUID) row[1], "PAID", "RESERVED", "PAID", (BigDecimal) row[2], id, Tenant.userId(), "Paid by bank transfer " + r.transferReference().trim());
        audit.record("WITHDRAWAL_PAID", "withdrawal", id, PartnerMail.naira(d.amount()) + " ref " + r.transferReference().trim());
        mail.withdrawalPaid(d.affiliateEmail(), d.affiliateName(), d.amount(), r.transferReference().trim());
        return get(id);
    }

    @Transactional
    public Dto markFailed(UUID id, String reason) {
        Dto d = lock(id);
        notOwnRequest(id);
        if (jdbc.update("update affiliate_withdrawals set status = 'FAILED', closed_reason = ?, closed_by = ?, closed_at = now(), updated_at = now() where id = ? and status = 'PROCESSING'", reason.trim(), Tenant.userId(), id) != 1)
            throw ApiException.conflict("Only a withdrawal that is being processed can be marked as failed (it is " + d.status().toLowerCase(Locale.ROOT) + ")");
        commissions.releaseReservations(id, Tenant.userId(), "Payout failed");
        audit.record("WITHDRAWAL_FAILED", "withdrawal", id, reason.trim());
        mail.withdrawalFailed(d.affiliateEmail(), d.affiliateName(), d.amount(), reason.trim());
        return get(id);
    }

    /** The full account number, shown once to the administrator who has to make the transfer. Only while a transfer is due, and always logged. */
    @Transactional
    public Map<String, String> bankDetails(UUID id) {
        Dto d = get(id);
        if (!"APPROVED".equals(d.status()) && !"PROCESSING".equals(d.status())) throw ApiException.conflict("Bank details are only shown while a transfer is due");
        notOwnRequest(id);
        var l = jdbc.query("select bank_holder_name, bank_name, bank_code, account_number_enc from affiliate_withdrawals where id = ?",
            (rs, i) -> Map.of("holderName", rs.getString(1), "bankName", rs.getString(2), "bankCode", rs.getString(3) == null ? "" : rs.getString(3), "accountNumber", box.decrypt(rs.getString(4)),
                "amount", d.amount().toPlainString()), id);
        audit.record("BANK_DETAILS_VIEWED", "withdrawal", id, "for payout of " + PartnerMail.naira(d.amount()));
        return l.get(0);
    }
}
