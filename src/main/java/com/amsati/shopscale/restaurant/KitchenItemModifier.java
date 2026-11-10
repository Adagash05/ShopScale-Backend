package com.amsati.shopscale.restaurant;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "kitchen_item_modifiers")
@Getter @Setter @NoArgsConstructor
public class KitchenItemModifier {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID itemId;
    private UUID modifierId;
    private String name;
    private BigDecimal priceDelta;
}
