package com.amsati.shopscale.transfer;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "stock_transfer_items")
@Getter @Setter @NoArgsConstructor
public class StockTransferItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID transferId;
    private UUID businessId;
    private UUID variantId;
    private BigDecimal quantity;
    private BigDecimal quantityReceived;
}
