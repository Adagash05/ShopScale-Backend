package com.amsati.shopscale.restaurant;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "restaurant_tables")
@Getter @Setter @NoArgsConstructor
public class RestaurantTable {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID branchId;
    private String name;
    private int seats = 2;
    private boolean active = true;
}
