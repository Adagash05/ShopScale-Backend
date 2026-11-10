package com.amsati.shopscale.inventory;

import com.amsati.shopscale.branch.Branch;
import com.amsati.shopscale.branch.BranchRepository;
import com.amsati.shopscale.branch.BranchStock;
import com.amsati.shopscale.branch.BranchStockRepository;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.common.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The single place that changes stock. Always call inside a transaction with the variant row locked.
 * Stock is kept per branch; the variant's total quantity is the sum of its branches.
 */
@Service
@RequiredArgsConstructor
public class StockService {
    private final StockMovementRepository movements;
    private final BranchStockRepository branchStock;
    private final BranchRepository branches;
    private final com.amsati.shopscale.notification.NotificationService notify;

    /** Apply a change at the business's main branch. */
    public StockMovement apply(ProductVariant v, MovementType type, BigDecimal change, String note, String reference,
                               UUID userId, boolean allowNegative) {
        return apply(v, null, type, change, note, reference, userId, allowNegative);
    }

    /** Apply a change at a specific branch (null = main branch). */
    public StockMovement apply(ProductVariant v, UUID branchId, MovementType type, BigDecimal change, String note,
                               String reference, UUID userId, boolean allowNegative) {
        UUID bid = v.getBusinessId();
        UUID branch = resolveBranch(bid, branchId);
        BranchStock bs = branchStock.lockOne(bid, branch, v.getId()).orElseGet(() -> branchStock.save(new BranchStock(branch, v.getId(), bid)));
        BigDecimal after = bs.getQuantity().add(change);
        if (after.signum() < 0 && !allowNegative) throw ApiException.conflict("Not enough stock at this branch");
        bs.setQuantity(after);
        bs.setUpdatedAt(Instant.now());
        BigDecimal total = v.getQuantity().add(change);
        v.setQuantity(total);
        v.setUpdatedAt(Instant.now());
        StockMovement m = new StockMovement();
        m.setBusinessId(bid);
        m.setVariantId(v.getId());
        m.setBranchId(branch);
        m.setType(type);
        m.setQuantityChange(change);
        m.setBalanceAfter(after);
        m.setNote(note);
        m.setReference(reference);
        m.setCreatedBy(userId);
        StockMovement saved = movements.save(m);
        BigDecimal low = v.getLowStockThreshold();
        if (change.signum() < 0 && low != null && low.signum() > 0 && total.compareTo(low) <= 0 && total.subtract(change).compareTo(low) > 0) {
            String name = v.getProduct().getName() + (v.getLabel() == null || v.getLabel().isBlank() ? "" : " (" + v.getLabel() + ")");
            notify.publish(bid, com.amsati.shopscale.security.Permission.STOCK_VIEW, "LOW_STOCK", name + " is running low",
                "Only " + total.stripTrailingZeros().toPlainString() + " left.", "/stock", "low:" + v.getId() + ":" + java.time.LocalDate.now());
        }
        return saved;
    }

    /** Quantity on hand at one branch (null = main). */
    public BigDecimal onHand(ProductVariant v, UUID branchId) {
        UUID bid = v.getBusinessId();
        return branchStock.lockOne(bid, resolveBranch(bid, branchId), v.getId()).map(BranchStock::getQuantity).orElse(BigDecimal.ZERO);
    }

    public UUID resolveBranch(UUID businessId, UUID branchId) {
        if (branchId == null) {
            return branches.findMain(businessId).map(Branch::getId).orElseThrow(() -> ApiException.conflict("This business has no main branch"));
        }
        Branch b = branches.findByIdAndBusinessId(branchId, businessId).orElseThrow(() -> ApiException.notFound("Branch not found"));
        if (!b.isActive()) throw ApiException.conflict("That branch is not active");
        return b.getId();
    }
}
