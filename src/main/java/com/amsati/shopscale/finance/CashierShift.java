package com.amsati.shopscale.finance;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "cashier_shifts")
@Getter @Setter @NoArgsConstructor
public class CashierShift {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID branchId;
    private UUID userId;
    private BigDecimal openingFloat = BigDecimal.ZERO;
    private Instant openedAt = Instant.now();
    private Instant closedAt;
    private BigDecimal expectedCash;
    private BigDecimal countedCash;
    private BigDecimal variance;
    private String note;
    private String status = "OPEN";
    private UUID reviewedBy;
    private Instant reviewedAt;
    private String reviewNote;
}
