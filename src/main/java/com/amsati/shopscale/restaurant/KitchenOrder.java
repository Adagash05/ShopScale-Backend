package com.amsati.shopscale.restaurant;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "kitchen_orders")
@Getter @Setter @NoArgsConstructor
public class KitchenOrder {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID branchId;
    private long number;
    private UUID tableId;
    private String orderType;
    private String customerName;
    private String note;
    private String status = "OPEN";
    private UUID saleId;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
}
