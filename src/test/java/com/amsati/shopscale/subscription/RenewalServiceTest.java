package com.amsati.shopscale.subscription;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RenewalServiceTest {
    @Test void referenceIsDeterministicPerSubscriptionPeriodAndAttempt() {
        UUID sub = UUID.randomUUID();
        Instant due = Instant.parse("2026-12-15T10:00:00Z");
        assertEquals(RenewalService.reference(sub, due, 1), RenewalService.reference(sub, due, 1));
        assertNotEquals(RenewalService.reference(sub, due, 1), RenewalService.reference(sub, due, 2));
        assertNotEquals(RenewalService.reference(sub, due, 1), RenewalService.reference(sub, due.plusSeconds(1), 1));
        assertTrue(RenewalService.reference(sub, due, 1).startsWith("SSR-"));
        assertTrue(RenewalService.reference(sub, due, 1).length() <= 80);
    }

    @Test void failureTextIsShortAndSingleLine() {
        assertNull(RenewalService.safe(null));
        assertNull(RenewalService.safe("  "));
        assertEquals("a b", RenewalService.safe("a\r\nb"));
        assertEquals(200, RenewalService.safe("x".repeat(500)).length());
    }
}
