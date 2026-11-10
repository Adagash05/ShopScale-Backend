package com.amsati.shopscale.purchasing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "purchases")
@Getter @Setter @NoArgsConstructor
public class Purchase {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID branchId;
    private UUID purchaseOrderId;
    private long purchaseNumber;
    private UUID supplierId;
    private String reference;
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private com.amsati.shopscale.common.PaymentMethod paymentMethod;
    private BigDecimal total;
    private BigDecimal amountPaid;
    private BigDecimal amountOwed;
    private String note;
    private String clientRequestId;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
}
