package com.amsati.shopscale.subscription;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** What a business is currently allowed to do. All plan rules are computed here, on the server. */
public record SubscriptionState(
        String planCode,
        String planName,
        String status,          // effective status, e.g. TRIALING, ACTIVE, GRACE_PERIOD, CANCELLED, EXPIRED, SUSPENDED
        BillingCycle billingCycle,
        Instant periodEnd,
        boolean writable,
        int maxUsers,
        int maxLocations,
        Integer maxItems,       // null = unlimited
        Set<String> features) {

    /**
     * Effective state at a point in time. Stored status is a hint; the period end and grace window decide access.
     * SUSPENDED and EXPIRED are never writable. CANCELLED keeps access until the paid period ends, with no grace.
     */
    public static SubscriptionState compute(Subscription s, Plan p, Instant now, int graceDays) {
        boolean withinPeriod = now.isBefore(s.getCurrentPeriodEnd());
        boolean inGrace = !withinPeriod && now.isBefore(s.getCurrentPeriodEnd().plus(Duration.ofDays(graceDays)));
        SubscriptionStatus stored = s.getStatus();
        String status;
        boolean writable;
        if (stored == SubscriptionStatus.SUSPENDED) {
            status = "SUSPENDED"; writable = false;
        } else if (stored == SubscriptionStatus.EXPIRED) {
            status = "EXPIRED"; writable = false;
        } else if (withinPeriod) {
            status = stored.name(); writable = true;
        } else if (inGrace && stored != SubscriptionStatus.CANCELLED && stored != SubscriptionStatus.TRIALING) {
            status = "GRACE_PERIOD"; writable = true;
        } else {
            status = "EXPIRED"; writable = false;
        }
        return new SubscriptionState(p.getCode(), p.getName(), status, s.getBillingCycle(),
            s.getCurrentPeriodEnd(), writable, p.getMaxUsers(), p.getMaxLocations(), p.getMaxItems(), Set.copyOf(p.getFeatures()));
    }

    public boolean hasFeature(String f) { return features.contains(f); }
}
