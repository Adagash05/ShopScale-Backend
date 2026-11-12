package com.amsati.shopscale.store;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "store_settings")
@Getter @Setter @NoArgsConstructor
public class StoreSettings {
    @Id
    private UUID businessId;
    private boolean enabled;
    private String title;
    private String description;
    private String phone;
    private String whatsapp;
    private boolean pickupEnabled = true;
    private boolean deliveryEnabled;
    private BigDecimal deliveryFee = BigDecimal.ZERO;
    private String deliveryNote;
    private boolean payLaterEnabled = true;
    private UUID branchId;
    private String paystackPublicKey;
    private String paystackSecretEnc;
    private Instant paystackConnectedAt;
    private String customDomain;
    private String domainToken;
    private boolean domainVerified;
    private String logoType;
    private String instagramUrl;
    private String facebookUrl;
    private String xUrl;
    private String tiktokUrl;
    private String openingHours;
    private Instant updatedAt = Instant.now();
}
