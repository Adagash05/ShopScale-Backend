package com.amsati.shopscale.sales;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sale_refund_items")
@Getter @Setter @NoArgsConstructor
public class SaleRefundItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID refundId;
    private UUID saleItemId;
    private BigDecimal quantity;
    private BigDecimal amount;
    private boolean returnedToStock = true;
}
