package com.amsati.shopscale.subscription;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * The "billing month" used for monthly allowances (expense records on Essential, WhatsApp reminders).
 * It is anchored on the start of the current subscription period, not on the calendar and not on a login, so it cannot be
 * reset by logging out, switching device or restarting the server. A yearly subscription still gets twelve monthly windows.
 * Pure, so the rule can be tested without Spring.
 */
public final class UsagePeriod {
    private UsagePeriod() {}

    public record Window(Instant start, Instant end) {
        public boolean contains(Instant t) { return !t.isBefore(start) && t.isBefore(end); }
    }

    public static Window billingMonth(Instant anchor, Instant now) {
        ZonedDateTime n = now.atZone(ZoneOffset.UTC);
        if (anchor == null) {   // no subscription period to anchor on (permanent Free fallback): calendar month, UTC
            ZonedDateTime s = n.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            return new Window(s.toInstant(), s.plusMonths(1).toInstant());
        }
        ZonedDateTime a = anchor.atZone(ZoneOffset.UTC);
        if (n.isBefore(a)) return new Window(a.toInstant(), a.plusMonths(1).toInstant());
        long k = ChronoUnit.MONTHS.between(a, n);
        while (a.plusMonths(k).isAfter(n)) k--;
        while (!a.plusMonths(k + 1).isAfter(n)) k++;
        return new Window(a.plusMonths(k).toInstant(), a.plusMonths(k + 1).toInstant());
    }

    /** True when {@code used} more units would go past the allowance. null = unlimited. */
    public static boolean exceeds(Integer limit, long usedAfter) {
        return limit != null && usedAfter > limit;
    }
}
