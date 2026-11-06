package com.amsati.shopscale.customer;

import com.amsati.shopscale.common.PaymentMethod;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customer_payments")
@Getter @Setter @NoArgsConstructor
public class CustomerPayment {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID customerId;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private PaymentMethod method;
    private String note;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
}
