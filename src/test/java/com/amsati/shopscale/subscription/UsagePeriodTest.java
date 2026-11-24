package com.amsati.shopscale.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UsagePeriodTest {
    static Instant t(String s) { return Instant.parse(s); }

    @Test void windowIsAnchoredOnThePeriodStartNotTheCalendar() {
        var w = UsagePeriod.billingMonth(t("2026-03-15T10:00:00Z"), t("2026-04-10T09:00:00Z"));
        assertEquals(t("2026-03-15T10:00:00Z"), w.start());
        assertEquals(t("2026-04-15T10:00:00Z"), w.end());
    }
    @Test void windowRollsOverExactlyOnTheBoundary() {
        var w = UsagePeriod.billingMonth(t("2026-03-15T10:00:00Z"), t("2026-04-15T10:00:00Z"));
        assertEquals(t("2026-04-15T10:00:00Z"), w.start());
        assertEquals(t("2026-05-15T10:00:00Z"), w.end());
    }
    @Test void aYearlySubscriptionStillGetsMonthlyWindows() {
        var w = UsagePeriod.billingMonth(t("2026-01-01T00:00:00Z"), t("2026-09-20T00:00:00Z"));
        assertEquals(t("2026-09-01T00:00:00Z"), w.start());
        assertEquals(t("2026-10-01T00:00:00Z"), w.end());
    }
    @Test void monthEndAnchorsDoNotDrift() {
        var w = UsagePeriod.billingMonth(t("2026-01-31T00:00:00Z"), t("2026-03-05T00:00:00Z"));
        assertEquals(t("2026-02-28T00:00:00Z"), w.start());
        assertEquals(t("2026-03-31T00:00:00Z"), w.end());
    }
    @Test void withoutAnAnchorTheCalendarMonthIsUsed() {
        var w = UsagePeriod.billingMonth(null, t("2026-11-24T12:00:00Z"));
        assertEquals(t("2026-11-01T00:00:00Z"), w.start());
        assertEquals(t("2026-12-01T00:00:00Z"), w.end());
        assertTrue(w.contains(t("2026-11-24T12:00:00Z")));
        assertFalse(w.contains(t("2026-12-01T00:00:00Z")));
    }
    @Test void exceedsTreatsNullAsUnlimited() {
        assertFalse(UsagePeriod.exceeds(null, 1_000_000));
        assertFalse(UsagePeriod.exceeds(10, 10));
        assertTrue(UsagePeriod.exceeds(10, 11));
        assertTrue(UsagePeriod.exceeds(0, 1));
    }
}
