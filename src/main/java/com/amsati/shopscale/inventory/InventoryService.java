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

    public enum AdjustType { RESTOCK, DAMAGED, CORRECTION, ADJUSTMENT }

    public record AdjustRequest(@NotNull UUID variantId, @NotNull AdjustType type, @NotNull Integer quantity,
                                @Size(max = 300) String note) {}

    public record AdjustResult(UUID variantId, int quantity) {}

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
        int q = r.quantity();
        int change;
        MovementType type;
        switch (r.type()) {
            case RESTOCK -> {
                if (q <= 0) throw ApiException.badRequest("Enter how many items you received");
                change = q; type = MovementType.RESTOCK;
            }
            case DAMAGED -> {
                if (q <= 0) throw ApiException.badRequest("Enter how many items are damaged");
                change = -q; type = MovementType.DAMAGED;
            }
            case ADJUSTMENT -> {
                if (q == 0) throw ApiException.badRequest("Enter a number other than zero");
                change = q; type = MovementType.ADJUSTMENT;
            }
            case CORRECTION -> {
                if (q < 0) throw ApiException.badRequest("The counted quantity cannot be negative");
                change = q - v.getQuantity(); type = MovementType.CORRECTION;
                if (change == 0) throw ApiException.badRequest("Stock already matches that count");
            }
            default -> throw ApiException.badRequest("Unknown adjustment type");
        }
        stock.apply(v, type, change, r.note(), null, Tenant.userId(), b.isAllowNegativeStock());
        audit.record(bid, Tenant.userId(), "STOCK_" + type.name(), "variant", v.getId(), "change " + change);
        return new AdjustResult(v.getId(), v.getQuantity());
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
