package com.amsati.shopscale.invoice;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "invoices")
@Getter @Setter @NoArgsConstructor
public class Invoice {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private long invoiceNumber;
    private UUID customerId;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String customerAddress;
    private String status = "DRAFT";
    private LocalDate issueDate;
    private LocalDate dueDate;
    private String paymentTerms;
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal lineDiscounts = BigDecimal.ZERO;
    private BigDecimal taxTotal = BigDecimal.ZERO;
    private BigDecimal invoiceDiscount = BigDecimal.ZERO;
    private BigDecimal total = BigDecimal.ZERO;
    private BigDecimal amountPaid = BigDecimal.ZERO;
    private String note;
    private String paymentInstructions;
    private UUID sourceSaleId;
    private UUID sourceDocumentId;
    private String shareToken;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private Instant sentAt;
    private Instant cancelledAt;
}
