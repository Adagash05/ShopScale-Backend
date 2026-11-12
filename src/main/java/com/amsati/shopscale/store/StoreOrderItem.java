package com.amsati.shopscale.store;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "store_order_items")
@Getter @Setter @NoArgsConstructor
public class StoreOrderItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID orderId;
    private UUID businessId;
    private UUID variantId;
    private String itemName;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal lineTotal;
    /** Per-unit total of paid options chosen on this line; already included in unitPrice. */
    private BigDecimal optionsTotal = BigDecimal.ZERO;
}
