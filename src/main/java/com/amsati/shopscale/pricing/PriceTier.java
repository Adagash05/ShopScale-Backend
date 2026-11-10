package com.amsati.shopscale.pricing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "price_tiers")
@Getter @Setter @NoArgsConstructor
public class PriceTier {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private String name;
    private boolean active = true;
    private Instant createdAt = Instant.now();
}
