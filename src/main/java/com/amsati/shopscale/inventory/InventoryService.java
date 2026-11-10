package com.amsati.shopscale.inventory;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.ProductStatus;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {
    private final ProductVariantRepository variants;
    private final StockMovementRepository movements;
    private final BusinessRepository businesses;
    private final StockService stock;
    private final SubscriptionService subscriptions;
    private final AuditService audit;
    private final AccessService access;
    private final com.amsati.shopscale.unit.UnitService units;

    public enum AdjustType { RESTOCK, DAMAGED, CORRECTION, ADJUSTMENT }

    public record AdjustRequest(@NotNull UUID variantId, @NotNull AdjustType type, @NotNull BigDecimal quantity,
                                @Size(max = 300) String note, UUID branchId) {}

    public record AdjustResult(UUID variantId, BigDecimal quantity) {}

    /**
     * RESTOCK adds, DAMAGED removes, ADJUSTMENT applies a signed change, CORRECTION sets the counted quantity.
     */
    @Transactional
    public AdjustResult adjust(AdjustRequest r) {
        UUID bid = Tenant.businessId();
        // Adding stock and correcting stock are separate permissions.
        access.require(r.type() == AdjustType.RESTOCK ? Permission.STOCK_RESTOCK : Permission.STOCK_ADJUST);
        Business b = businesses.findById(bid).orElseThrow();
        List<ProductVariant> locked = variants.lockAll(bid, List.of(r.variantId()));
        if (locked.isEmpty()) throw ApiException.notFound("Product variant not found");
        ProductVariant v = locked.get(0);
        BigDecimal q = r.quantity();
        BigDecimal change;
        MovementType type;
        switch (r.type()) {
            case RESTOCK -> {
                if (q.signum() <= 0) throw ApiException.badRequest("Enter how many items you received");
                units.requireValidQuantity(v, q);
                change = q; type = MovementType.RESTOCK;
            }
            case DAMAGED -> {
                if (q.signum() <= 0) throw ApiException.badRequest("Enter how many items are damaged");
                units.requireValidQuantity(v, q);
                change = q.negate(); type = MovementType.DAMAGED;
            }
            case ADJUSTMENT -> {
                if (q.signum() == 0) throw ApiException.badRequest("Enter a number other than zero");
                change = q; type = MovementType.ADJUSTMENT;
            }
            case CORRECTION -> {
                if (q.signum() < 0) throw ApiException.badRequest("The counted quantity cannot be negative");
                if (q.signum() > 0) units.requireValidQuantity(v, q);
                change = q.subtract(stock.onHand(v, r.branchId())); type = MovementType.CORRECTION;
                if (change.signum() == 0) throw ApiException.badRequest("Stock already matches that count");
            }
            default -> throw ApiException.badRequest("Unknown adjustment type");
        }
        BigDecimal balance = stock.apply(v, r.branchId(), type, change, r.note(), null, Tenant.userId(), b.isAllowNegativeStock()).getBalanceAfter();
        audit.record(bid, Tenant.userId(), "STOCK_" + type.name(), "variant", v.getId(), "change " + change.stripTrailingZeros().toPlainString());
        return new AdjustResult(v.getId(), balance);
    }

    @Transactional(readOnly = true)
    public Page<MovementDto> history(UUID variantId, int page, int size) {
        UUID bid = Tenant.businessId();
        subscriptions.requireFeature(bid, "STOCK_HISTORY", "Stock history");
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return variantId == null ? movements.history(bid, pr) : movements.historyForVariant(bid, variantId, pr);
    }

    @Transactional(readOnly = true)
    public List<LowStockDto> lowStock() {
        return variants.lowStock(Tenant.businessId(), ProductStatus.ACTIVE);
    }
}
