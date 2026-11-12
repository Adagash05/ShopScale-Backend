package com.amsati.shopscale.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RenewalDatesTest {
    private static Instant t(String s) { return Instant.parse(s); }
    private static Instant next(String from, String anchor, BillingCycle c) { return RenewalDates.periodEnd(t(from), t(anchor), c); }

    @Test void plainMonth() { assertEquals(t("2026-07-15T10:00:00Z"), next("2026-06-15T10:00:00Z", "2026-06-15T10:00:00Z", BillingCycle.MONTHLY)); }

    @Test void endOfMonthDoesNotDrift() {
        Instant a = t("2026-01-31T09:30:00Z");
        Instant feb = RenewalDates.periodEnd(a, a, BillingCycle.MONTHLY);
        assertEquals(t("2026-02-28T09:30:00Z"), feb);
        Instant mar = RenewalDates.periodEnd(feb, a, BillingCycle.MONTHLY);
        assertEquals(t("2026-03-31T09:30:00Z"), mar);
        assertEquals(t("2026-04-30T09:30:00Z"), RenewalDates.periodEnd(mar, a, BillingCycle.MONTHLY));
    }

    @Test void leapYearFebruary() {
        assertEquals(t("2028-02-29T00:00:00Z"), next("2028-01-31T00:00:00Z", "2028-01-31T00:00:00Z", BillingCycle.MONTHLY));
        assertEquals(t("2027-02-28T00:00:00Z"), next("2027-01-30T00:00:00Z", "2027-01-30T00:00:00Z", BillingCycle.MONTHLY));
    }

    @Test void annualFromLeapDay() {
        Instant a = t("2028-02-29T12:00:00Z");
        Instant y1 = RenewalDates.periodEnd(a, a, BillingCycle.ANNUAL);
        assertEquals(t("2029-02-28T12:00:00Z"), y1);
        Instant y4 = RenewalDates.periodEnd(RenewalDates.periodEnd(RenewalDates.periodEnd(y1, a, BillingCycle.ANNUAL), a, BillingCycle.ANNUAL), a, BillingCycle.ANNUAL);
        assertEquals(t("2032-02-29T12:00:00Z"), y4);
    }

    @Test void annualIsNotThreeSixtyFiveDays() { assertEquals(t("2029-03-01T00:00:00Z"), next("2028-03-01T00:00:00Z", "2028-03-01T00:00:00Z", BillingCycle.ANNUAL)); }

    @Test void decemberRollsIntoNextYear() { assertEquals(t("2027-01-10T08:00:00Z"), next("2026-12-10T08:00:00Z", "2026-12-10T08:00:00Z", BillingCycle.MONTHLY)); }
}
