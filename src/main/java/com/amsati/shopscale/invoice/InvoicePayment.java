package com.amsati.shopscale.invoice;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "invoice_payments")
@Getter @Setter @NoArgsConstructor
public class InvoicePayment {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID invoiceId;
    private UUID businessId;
    private BigDecimal amount;
    private String method;
    private String note;
    private String clientRequestId;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
}
