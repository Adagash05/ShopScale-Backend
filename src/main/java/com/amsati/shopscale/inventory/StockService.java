package com.amsati.shopscale.inventory;

import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.common.ApiException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The single place that changes a variant's quantity. Always call it inside a transaction with the variant row-locked. */
@Service
@RequiredArgsConstructor
public class StockService {
    private final StockMovementRepository movements;

    public StockMovement apply(ProductVariant v, MovementType type, int change, String note, String reference,
                               UUID userId, boolean allowNegative) {
        int after = v.getQuantity() + change;
        if (after < 0 && !allowNegative) throw ApiException.conflict("Not enough stock");
        v.setQuantity(after);
        v.setUpdatedAt(Instant.now());
        StockMovement m = new StockMovement();
        m.setBusinessId(v.getBusinessId());
        m.setVariantId(v.getId());
        m.setType(type);
        m.setQuantityChange(change);
        m.setBalanceAfter(after);
        m.setNote(note);
        m.setReference(reference);
        m.setCreatedBy(userId);
        return movements.save(m);
    }
}
