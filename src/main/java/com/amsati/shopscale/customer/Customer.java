package com.amsati.shopscale.customer;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customers")
@Getter @Setter @NoArgsConstructor
public class Customer {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private String name;
    private String phone;
    private String email;
    private String address;
    private String notes;
    private String customerType = "RETAIL";
    private UUID priceTierId;
    private BigDecimal balance = BigDecimal.ZERO;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
