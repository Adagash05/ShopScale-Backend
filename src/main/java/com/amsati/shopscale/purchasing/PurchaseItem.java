package com.amsati.shopscale.purchasing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "purchase_items")
@Getter @Setter @NoArgsConstructor
public class PurchaseItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID purchaseId;
    private UUID businessId;
    private UUID variantId;
    private String productName;
    private String variantLabel;
    private java.math.BigDecimal quantity;
    private String unit;
    private BigDecimal unitCost;
    private BigDecimal lineTotal;
}
