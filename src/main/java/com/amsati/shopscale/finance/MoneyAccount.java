package com.amsati.shopscale.finance;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "money_accounts")
@Getter @Setter @NoArgsConstructor
public class MoneyAccount {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private String name;
    private String kind;
    private BigDecimal openingBalance = BigDecimal.ZERO;
    private boolean active = true;
    private Instant createdAt = Instant.now();
}
