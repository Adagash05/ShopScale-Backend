package com.amsati.shopscale.recipe;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "recipes")
@Getter @Setter @NoArgsConstructor
public class Recipe {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID variantId;
    private BigDecimal yieldQuantity = BigDecimal.ONE;
    private String mode = "ON_SALE";
    private String notes;
    private Instant updatedAt = Instant.now();
}
