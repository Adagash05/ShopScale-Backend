package com.amsati.shopscale.finance;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "money_movements")
@Getter @Setter @NoArgsConstructor
public class MoneyMovement {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID accountId;
    private UUID branchId;
    private String direction;
    private BigDecimal amount;
    private String method;
    private String kind;
    private String refType;
    private UUID refId;
    private String note;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
}
