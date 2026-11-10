package com.amsati.shopscale.pricing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "variant_prices")
@Getter @Setter @NoArgsConstructor
public class VariantPrice {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID variantId;
    private UUID priceTierId;
    private UUID customerId;
    private BigDecimal minQuantity = BigDecimal.ONE;
    private BigDecimal price;
    private Instant createdAt = Instant.now();
}
