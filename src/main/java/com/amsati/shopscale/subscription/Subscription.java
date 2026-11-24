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
    private boolean autoRenew;
    /** Start of the current chain of paid periods; renewals keep its day-of-month. */
    private Instant billingAnchor;
    private Instant cancelledAt;
    private Instant autoRenewConsentAt;
    /** A price agreed before a plan price change, for one plan and one billing cycle (see V30). Null for everyone else. */
    private String lockedPlanCode;
    @Enumerated(EnumType.STRING)
    private BillingCycle lockedCycle;
    private java.math.BigDecimal lockedPrice;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    /** What this subscriber pays per period on {@code plan} in their current billing cycle: their agreed price while it still applies, otherwise the list price. */
    public java.math.BigDecimal effectivePrice(Plan plan) {
        if (plan == null) return null;
        // The agreed price only counts for the plan the subscriber is actually on (plan.getCode() must equal planCode).
        if (!plan.getCode().equals(planCode)) return PricePolicy.effective(plan.getCode(), billingCycle, plan.getMonthlyPrice(), plan.getAnnualPrice(), null, null, null);
        return PricePolicy.effective(planCode, billingCycle, plan.getMonthlyPrice(), plan.getAnnualPrice(), lockedPlanCode, lockedCycle, lockedPrice);
    }

    /** The subscriber chose a plan or cycle: from here on the list price applies. */
    public void clearLockedPrice() { lockedPlanCode = null; lockedCycle = null; lockedPrice = null; }
}
