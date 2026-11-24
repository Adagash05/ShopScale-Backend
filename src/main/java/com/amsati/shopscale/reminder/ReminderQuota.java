package com.amsati.shopscale.reminder;

import com.amsati.shopscale.subscription.UsagePeriod;

/** The reminder allowance rule, pure so it can be tested without Spring or a database. limit null = unlimited, 0 = none. */
public final class ReminderQuota {
    private ReminderQuota() {}

    public enum Decision { ALLOWED, EXHAUSTED, NOT_INCLUDED }

    /** @param usedInWindow reminders already counted this billing month (not counting the one being requested) */
    public static Decision decide(Integer limit, long usedInWindow) {
        if (limit == null) return Decision.ALLOWED;
        if (limit <= 0) return Decision.NOT_INCLUDED;
        return UsagePeriod.exceeds(limit, usedInWindow + 1) ? Decision.EXHAUSTED : Decision.ALLOWED;
    }

    /** Reminders left this month; null = unlimited. Never negative (a business can be over after a downgrade). */
    public static Integer remaining(Integer limit, long used) {
        return limit == null ? null : (int) Math.max(0, limit - used);
    }
}
