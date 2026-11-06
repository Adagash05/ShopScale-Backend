package com.amsati.shopscale.business;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "businesses")
@Getter @Setter @NoArgsConstructor
public class Business {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String name;
    private String slug;
    @Enumerated(EnumType.STRING)
    private BusinessType businessType;
    private String subtype;
    private String description;
    private String phone;
    private String email;
    private String address;
    private String currency = "NGN";
    private String timezone = "Africa/Lagos";
    private boolean allowNegativeStock;
    private long saleCounter;
    private long purchaseCounter;
    private String status = "ACTIVE";
    private Instant createdAt = Instant.now();

    public ZoneId zone() { return ZoneId.of(timezone); }
}
