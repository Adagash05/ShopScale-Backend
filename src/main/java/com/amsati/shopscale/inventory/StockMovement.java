package com.amsati.shopscale.inventory;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "stock_movements")
@Getter @Setter @NoArgsConstructor
public class StockMovement {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID variantId;
    @Enumerated(EnumType.STRING)
    private MovementType type;
    private UUID branchId;
    private java.math.BigDecimal quantityChange;
    private java.math.BigDecimal balanceAfter;
    private String note;
    private String reference;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
}
