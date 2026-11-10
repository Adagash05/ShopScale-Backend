package com.amsati.shopscale.store;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "store_orders")
@Getter @Setter @NoArgsConstructor
public class StoreOrder {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private long number;
    private String publicToken;
    private String customerName;
    private String phone;
    private String email;
    private String deliveryMethod;
    private String address;
    private String note;
    private String status;
    private String paymentMode;
    private String paymentStatus = "UNPAID";
    private BigDecimal subtotal;
    private BigDecimal deliveryFee = BigDecimal.ZERO;
    private BigDecimal total;
    private String paystackReference;
    private Instant paidAt;
    private UUID saleId;
    private UUID branchId;
    private String attention;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
