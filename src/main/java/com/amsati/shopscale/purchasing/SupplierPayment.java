package com.amsati.shopscale.purchasing;

import com.amsati.shopscale.common.PaymentMethod;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "supplier_payments")
@Getter @Setter @NoArgsConstructor
public class SupplierPayment {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID supplierId;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private PaymentMethod method;
    private String note;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
}
