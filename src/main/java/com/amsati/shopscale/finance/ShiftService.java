package com.amsati.shopscale.finance;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.auth.User;
import com.amsati.shopscale.auth.UserRepository;
import com.amsati.shopscale.branch.BranchRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cashier shifts. Expected cash = opening float + the cash this person received minus cash they paid out (refunds etc.)
 * while the shift was open, taken from the money ledger. Closing records what was counted and the difference.
 */
@Service
@RequiredArgsConstructor
public class ShiftService {
    private final CashierShiftRepository shifts;
    private final MoneyMovementRepository movements;
    private final StockService stock;
    private final UserRepository users;
    private final BranchRepository branches;
    private final SubscriptionService subscriptions;
    private final AccessService access;
    private final AuditService audit;

    public record OpenRequest(@DecimalMin(value = "0", message = "Opening cash cannot be negative") BigDecimal openingFloat, UUID branchId) {}
    public record CloseRequest(@NotNull @DecimalMin(value = "0", message = "Counted cash cannot be negative") BigDecimal countedCash, @Size(max = 300) String note) {}
    public record ReviewRequest(@Size(max = 300) String note) {}
    public record ShiftDto(UUID id, UUID userId, String userName, UUID branchId, String branchName, BigDecimal openingFloat, Instant openedAt,
                           Instant closedAt, BigDecimal expectedCash, BigDecimal countedCash, BigDecimal variance, String note, String status,
                           boolean reviewed, String reviewNote) {}

    @Transactional
    public ShiftDto open(OpenRequest r) {
        UUID bid = Tenant.businessId();
        UUID uid = Tenant.userId();
        subscriptions.requireWritable(bid);
        if (shifts.findByBusinessIdAndUserIdAndStatus(bid, uid, "OPEN").isPresent()) throw ApiException.conflict("You already have a shift open");
        CashierShift s = new CashierShift();
        s.setBusinessId(bid);
        s.setUserId(uid);
        s.setBranchId(stock.resolveBranch(bid, r == null ? null : r.branchId()));
        s.setOpeningFloat(r == null || r.openingFloat() == null ? BigDecimal.ZERO : Money.scale(r.openingFloat()));
        shifts.save(s);
        audit.record(bid, uid, "SHIFT_OPENED", "shift", s.getId(), "Float " + s.getOpeningFloat().toPlainString());
        return dto(s, null, null);
    }

    @Transactional(readOnly = true)
    public Optional<ShiftDto> current() {
        UUID bid = Tenant.businessId();
        return shifts.findByBusinessIdAndUserIdAndStatus(bid, Tenant.userId(), "OPEN").map(s -> dto(s, null, null));
    }

    /** What the till should hold right now for the open shift. */
    @Transactional(readOnly = true)
    public BigDecimal expectedNow(CashierShift s) {
        BigDecimal net = movements.netCashBy(s.getBusinessId(), s.getUserId(), s.getOpenedAt(), Instant.now(), s.getBranchId());
        return Money.scale(s.getOpeningFloat().add(net == null ? BigDecimal.ZERO : net));
    }

    @Transactional
    public ShiftDto close(UUID id, CloseRequest r) {
        UUID bid = Tenant.businessId();
        CashierShift s = shifts.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Shift not found"));
        if (!s.getUserId().equals(Tenant.userId()) && !access.current().contains(Permission.SHIFTS_RECONCILE)) throw ApiException.forbidden("You can only close your own shift");
        if (!"OPEN".equals(s.getStatus())) throw ApiException.conflict("This shift is already closed");
        Instant now = Instant.now();
        BigDecimal net = movements.netCashBy(bid, s.getUserId(), s.getOpenedAt(), now, s.getBranchId());
        BigDecimal expected = Money.scale(s.getOpeningFloat().add(net == null ? BigDecimal.ZERO : net));
        BigDecimal counted = Money.scale(r.countedCash());
        s.setClosedAt(now);
        s.setExpectedCash(expected);
        s.setCountedCash(counted);
        s.setVariance(counted.subtract(expected));
        s.setNote(r.note() == null || r.note().isBlank() ? null : r.note().trim());
        s.setStatus("CLOSED");
        audit.record(bid, Tenant.userId(), "SHIFT_CLOSED", "shift", id, "Expected " + expected.toPlainString() + " counted " + counted.toPlainString());
        return dto(s, null, null);
    }

    @Transactional
    public ShiftDto review(UUID id, String note) {
        UUID bid = Tenant.businessId();
        subscriptions.requireFeature(bid, "CASH_RECONCILIATION", "Cashier reconciliation");
        CashierShift s = shifts.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Shift not found"));
        if (!"CLOSED".equals(s.getStatus())) throw ApiException.conflict("Close the shift before reviewing it");
        s.setReviewedBy(Tenant.userId());
        s.setReviewedAt(Instant.now());
        s.setReviewNote(note == null || note.isBlank() ? null : note.trim());
        audit.record(bid, Tenant.userId(), "SHIFT_REVIEWED", "shift", id, s.getReviewNote());
        return dto(s, null, null);
    }

    @Transactional(readOnly = true)
    public Page<ShiftDto> list(int page, int size) {
        UUID bid = Tenant.businessId();
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<CashierShift> p = access.current().contains(Permission.SHIFTS_RECONCILE)
            ? shifts.findByBusinessIdOrderByOpenedAtDesc(bid, pr)
            : shifts.findByBusinessIdAndUserIdOrderByOpenedAtDesc(bid, Tenant.userId(), pr);
        Map<UUID, String> names = users.findAllById(p.getContent().stream().map(CashierShift::getUserId).collect(Collectors.toSet())).stream()
            .collect(Collectors.toMap(User::getId, User::getFullName));
        Map<UUID, String> bn = branches.findByBusinessIdOrderByMainDescNameAsc(bid).stream()
            .collect(Collectors.toMap(com.amsati.shopscale.branch.Branch::getId, com.amsati.shopscale.branch.Branch::getName));
        return p.map(s -> dto(s, names, bn));
    }

    private ShiftDto dto(CashierShift s, Map<UUID, String> names, Map<UUID, String> branchNames) {
        String name = names != null ? names.get(s.getUserId()) : users.findById(s.getUserId()).map(User::getFullName).orElse(null);
        String bname = branchNames != null ? branchNames.get(s.getBranchId()) : branches.findById(s.getBranchId()).map(com.amsati.shopscale.branch.Branch::getName).orElse(null);
        BigDecimal expected = s.getExpectedCash() != null ? s.getExpectedCash() : ("OPEN".equals(s.getStatus()) ? expectedNow(s) : null);
        return new ShiftDto(s.getId(), s.getUserId(), name, s.getBranchId(), bname, s.getOpeningFloat(), s.getOpenedAt(), s.getClosedAt(), expected,
            s.getCountedCash(), s.getVariance(), s.getNote(), s.getStatus(), s.getReviewedAt() != null, s.getReviewNote());
    }
}
