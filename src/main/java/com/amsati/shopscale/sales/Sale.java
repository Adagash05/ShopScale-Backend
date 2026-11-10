package com.amsati.shopscale.sales;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sales")
@Getter @Setter @NoArgsConstructor
public class Sale {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID branchId;
    private String channel = "POS";
    private java.math.BigDecimal surcharge = java.math.BigDecimal.ZERO;
    private java.math.BigDecimal refundedAmount = java.math.BigDecimal.ZERO;
    private java.math.BigDecimal refundedCost = java.math.BigDecimal.ZERO;
    private String voidReason;
    private UUID voidedBy;
    private long saleNumber;
    private UUID customerId;
    @Enumerated(EnumType.STRING)
    private SaleStatus status = SaleStatus.COMPLETED;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal total;
    private BigDecimal amountPaid;
    private BigDecimal amountOwed;
    private BigDecimal costTotal;
    private String note;
    private String clientRequestId;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
    private Instant refundedAt;
}
