package com.amsati.shopscale.purchasing;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "purchase_orders")
@Getter @Setter @NoArgsConstructor
public class PurchaseOrder {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID branchId;
    private long poNumber;
    private UUID supplierId;
    private String status;
    private LocalDate expectedDate;
    private String note;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
    private Instant closedAt;
}
