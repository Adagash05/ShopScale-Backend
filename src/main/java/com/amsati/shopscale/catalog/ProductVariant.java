package com.amsati.shopscale.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "product_variants")
@Getter @Setter @NoArgsConstructor
public class ProductVariant {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;
    private String label;
    private String sku;
    private String barcode;
    private BigDecimal price;
    private BigDecimal cost = BigDecimal.ZERO;
    /** Shown as the struck-through "was" price in the online store while higher than price. Never used to charge. */
    private BigDecimal compareAtPrice;
    private BigDecimal quantity = BigDecimal.ZERO;
    private BigDecimal lowStockThreshold = BigDecimal.valueOf(5);
    private String unit = "piece";
    private boolean allowDecimal;
    private boolean active = true;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
