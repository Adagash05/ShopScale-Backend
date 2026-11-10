package com.amsati.shopscale.restaurant;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "modifier_groups")
@Getter @Setter @NoArgsConstructor
public class ModifierGroup {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private String name;
    private boolean required;
    private int maxSelect = 1;
    private boolean active = true;
}
