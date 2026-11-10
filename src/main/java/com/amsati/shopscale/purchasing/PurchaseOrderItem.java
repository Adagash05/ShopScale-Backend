package com.amsati.shopscale.purchasing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "purchase_order_items")
@Getter @Setter @NoArgsConstructor
public class PurchaseOrderItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID purchaseOrderId;
    private UUID businessId;
    private UUID variantId;
    private BigDecimal quantityOrdered;
    private BigDecimal quantityReceived = BigDecimal.ZERO;
    private BigDecimal unitCost;
}
