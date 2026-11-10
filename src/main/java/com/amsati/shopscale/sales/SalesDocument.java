package com.amsati.shopscale.sales;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A quotation (kind QUOTE) or a customer order (kind ORDER). */
@Entity
@Table(name = "sales_documents")
@Getter @Setter @NoArgsConstructor
public class SalesDocument {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID branchId;
    private String kind;
    private long docNumber;
    private UUID customerId;
    private String status = "OPEN";
    private LocalDate validUntil;
    private LocalDate deliveryDate;
    private BigDecimal subtotal;
    private BigDecimal discount = BigDecimal.ZERO;
    private BigDecimal total;
    private BigDecimal depositAmount = BigDecimal.ZERO;
    private String depositMethod;
    private String note;
    private UUID saleId;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
}
