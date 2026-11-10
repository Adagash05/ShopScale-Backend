package com.amsati.shopscale.restaurant;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "kitchen_order_items")
@Getter @Setter @NoArgsConstructor
public class KitchenOrderItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID orderId;
    private UUID businessId;
    private UUID variantId;
    private String itemName;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal modifiersTotal = BigDecimal.ZERO;
    private String note;
    private String status = "NEW";
    private Instant createdAt = Instant.now();
}
