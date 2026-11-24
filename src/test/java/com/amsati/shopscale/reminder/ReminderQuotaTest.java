package com.amsati.shopscale.reminder;

import static com.amsati.shopscale.reminder.ReminderQuota.Decision.ALLOWED;
import static com.amsati.shopscale.reminder.ReminderQuota.Decision.EXHAUSTED;
import static com.amsati.shopscale.reminder.ReminderQuota.Decision.NOT_INCLUDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ReminderQuotaTest {
    @Test void freeHasNone() { assertEquals(NOT_INCLUDED, ReminderQuota.decide(0, 0)); }
    @Test void essentialAllowsTwentyAndStopsTheTwentyFirst() {
        assertEquals(ALLOWED, ReminderQuota.decide(20, 19));
        assertEquals(EXHAUSTED, ReminderQuota.decide(20, 20));
    }
    @Test void growthAllowsFiftyAndStopsTheFiftyFirst() {
        assertEquals(ALLOWED, ReminderQuota.decide(50, 49));
        assertEquals(EXHAUSTED, ReminderQuota.decide(50, 50));
    }
    @Test void businessIsNeverBlocked() {
        assertEquals(ALLOWED, ReminderQuota.decide(null, 0));
        assertEquals(ALLOWED, ReminderQuota.decide(null, 1_000_000));
    }
    @Test void remainingNeverGoesNegativeAndNullMeansUnlimited() {
        assertEquals(5, ReminderQuota.remaining(20, 15));
        assertEquals(0, ReminderQuota.remaining(20, 25));
        assertNull(ReminderQuota.remaining(null, 99));
    }
}
