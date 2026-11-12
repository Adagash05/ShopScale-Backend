package com.amsati.shopscale.subscription;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One automatic renewal attempt. See V19 for the uniqueness rule that prevents double charging. */
@Entity
@Table(name = "subscription_renewals")
@Getter @Setter @NoArgsConstructor
public class SubscriptionRenewal {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID subscriptionId;
    private UUID businessId;
    private Instant dueAt;
    private int attemptNo;
    private String status = "PENDING";
    private UUID paymentId;
    private String failureReason;
    private Instant nextRetryAt;
    private Instant createdAt = Instant.now();
    private Instant finishedAt;
}
