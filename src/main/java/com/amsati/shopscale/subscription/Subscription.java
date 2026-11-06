package com.amsati.shopscale.subscription;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "subscriptions")
@Getter @Setter @NoArgsConstructor
public class Subscription {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private String planCode;
    @Enumerated(EnumType.STRING)
    private BillingCycle billingCycle;
    @Enumerated(EnumType.STRING)
    private SubscriptionStatus status;
    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
