package com.amsati.shopscale.unit;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "unit_conversions")
@Getter @Setter @NoArgsConstructor
public class UnitConversion {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID variantId;
    private String unit;
    /** How many base units are in one of this unit. */
    private BigDecimal factor;
}
