package com.amsati.shopscale.transfer;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "stock_transfers")
@Getter @Setter @NoArgsConstructor
public class StockTransfer {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private long transferNumber;
    private UUID fromBranchId;
    private UUID toBranchId;
    private String status;
    private String note;
    private String clientRequestId;
    private UUID createdBy;
    private UUID receivedBy;
    private Instant createdAt = Instant.now();
    private Instant receivedAt;
}
