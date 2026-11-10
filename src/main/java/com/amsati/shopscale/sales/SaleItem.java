package com.amsati.shopscale.sales;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sale_items")
@Getter @Setter @NoArgsConstructor
public class SaleItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id")
    private Sale sale;
    private UUID businessId;
    private UUID variantId;
    private String productName;
    private String variantLabel;
    private java.math.BigDecimal quantity;
    private String unit;
    private java.math.BigDecimal quantityRefunded = java.math.BigDecimal.ZERO;
    private BigDecimal unitPrice;
    private BigDecimal unitCost;
    private BigDecimal lineTotal;
}
