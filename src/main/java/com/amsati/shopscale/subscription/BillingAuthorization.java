package com.amsati.shopscale.subscription;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A reusable Paystack authorization for ShopScale's own subscription billing. The code is stored encrypted and never leaves the backend. */
@Entity
@Table(name = "billing_authorizations")
@Getter @Setter @NoArgsConstructor
public class BillingAuthorization {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private String customerEmail;
    private String authorizationCodeEnc;
    private String signature;
    private String channel;
    private String cardType;
    private String bank;
    private String last4;
    private String expMonth;
    private String expYear;
    private boolean reusable;
    private boolean active = true;
    private String sourceReference;
    private Instant consentedAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
