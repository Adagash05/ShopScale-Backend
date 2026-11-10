package com.amsati.shopscale.restaurant;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "modifiers")
@Getter @Setter @NoArgsConstructor
public class Modifier {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID groupId;
    private UUID businessId;
    private String name;
    private BigDecimal priceDelta = BigDecimal.ZERO;
    private UUID ingredientVariantId;
    private BigDecimal ingredientQuantity;
    private boolean active = true;
}
