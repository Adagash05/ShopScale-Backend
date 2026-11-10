package com.amsati.shopscale.sales;

import com.amsati.shopscale.common.PaymentMethod;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sale_refunds")
@Getter @Setter @NoArgsConstructor
public class SaleRefund {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID saleId;
    private BigDecimal amount;
    private BigDecimal cashRefunded;
    private BigDecimal owedReduced;
    @Enumerated(EnumType.STRING)
    private PaymentMethod method;
    private String reason;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
}
