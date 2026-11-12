package com.amsati.shopscale.subscription;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Calendar-correct period arithmetic, always in UTC. A period is one calendar month or twelve calendar months, never 30 or 365 days.
 * The day-of-month comes from the anchor (where the chain of periods began), so a subscription that started on 31 January ends on
 * 28 (or 29) February and then on 31 March again, instead of drifting to the 28th for ever. A 29 February anchor ends on 28 February in
 * a common year and on 29 February in the next leap year.
 */
public final class RenewalDates {
    private RenewalDates() {}

    /** The end of the period that starts at {@code from}. */
    public static Instant periodEnd(Instant from, Instant anchor, BillingCycle cycle) {
        int months = cycle == BillingCycle.ANNUAL ? 12 : 1;
        LocalDateTime f = LocalDateTime.ofInstant(from, ZoneOffset.UTC);
        int anchorDay = LocalDateTime.ofInstant(anchor == null ? from : anchor, ZoneOffset.UTC).getDayOfMonth();
        YearMonth target = YearMonth.from(f).plusMonths(months);
        int day = Math.min(anchorDay, target.lengthOfMonth());
        return LocalDateTime.of(target.getYear(), target.getMonth(), day, f.getHour(), f.getMinute(), f.getSecond(), f.getNano()).toInstant(ZoneOffset.UTC);
    }
}
