package com.amsati.shopscale.subscription;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SubscriptionStateTest {
    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    private static Plan plan() {
        Plan p = new Plan();
        p.setCode("GROWTH");
        p.setName("Growth");
        p.setMaxUsers(3);
        p.setMaxItems(3000);
        p.setFeatures(Set.of("POS", "CUSTOMER_CREDIT"));
        return p;
    }

    private static Subscription sub(SubscriptionStatus status, Instant end) {
        Subscription s = new Subscription();
        s.setStatus(status);
        s.setBillingCycle(BillingCycle.MONTHLY);
        s.setCurrentPeriodStart(end.minus(Duration.ofDays(30)));
        s.setCurrentPeriodEnd(end);
        return s;
    }

    @Test
    void activeWithinPeriodIsWritable() {
        SubscriptionState st = SubscriptionState.compute(sub(SubscriptionStatus.ACTIVE, NOW.plus(Duration.ofDays(5))), plan(), NOW, 3);
        assertEquals("ACTIVE", st.status());
        assertTrue(st.writable());
        assertTrue(st.hasFeature("POS"));
        assertFalse(st.hasFeature("CUSTOM_DOMAINS"));
    }

    @Test
    void trialWithinPeriodIsWritableButNeverGetsGrace() {
        assertTrue(SubscriptionState.compute(sub(SubscriptionStatus.TRIALING, NOW.plus(Duration.ofDays(1))), plan(), NOW, 3).writable());
        SubscriptionState ended = SubscriptionState.compute(sub(SubscriptionStatus.TRIALING, NOW.minus(Duration.ofHours(1))), plan(), NOW, 3);
        assertEquals("EXPIRED", ended.status());
        assertFalse(ended.writable());
    }

    @Test
    void paidSubscriptionGetsGraceThenExpires() {
        SubscriptionState grace = SubscriptionState.compute(sub(SubscriptionStatus.ACTIVE, NOW.minus(Duration.ofDays(2))), plan(), NOW, 3);
        assertEquals("GRACE_PERIOD", grace.status());
        assertTrue(grace.writable());
        SubscriptionState expired = SubscriptionState.compute(sub(SubscriptionStatus.ACTIVE, NOW.minus(Duration.ofDays(4))), plan(), NOW, 3);
        assertEquals("EXPIRED", expired.status());
        assertFalse(expired.writable());
    }

    @Test
    void cancelledKeepsAccessUntilPeriodEndOnly() {
        assertTrue(SubscriptionState.compute(sub(SubscriptionStatus.CANCELLED, NOW.plus(Duration.ofDays(3))), plan(), NOW, 3).writable());
        assertFalse(SubscriptionState.compute(sub(SubscriptionStatus.CANCELLED, NOW.minus(Duration.ofHours(1))), plan(), NOW, 3).writable());
    }

    @Test
    void suspendedIsNeverWritable() {
        SubscriptionState st = SubscriptionState.compute(sub(SubscriptionStatus.SUSPENDED, NOW.plus(Duration.ofDays(20))), plan(), NOW, 3);
        assertEquals("SUSPENDED", st.status());
        assertFalse(st.writable());
    }
}
