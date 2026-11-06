package com.amsati.shopscale.common;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DateRangeTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @Test
    void todayAndYesterday() {
        assertEquals(new DateRange(TODAY, TODAY), DateRange.resolve("today", null, null, TODAY));
        assertEquals(new DateRange(TODAY.minusDays(1), TODAY.minusDays(1)), DateRange.resolve("yesterday", null, null, TODAY));
    }

    @Test
    void rollingWindowsIncludeToday() {
        assertEquals(new DateRange(LocalDate.of(2026, 3, 9), TODAY), DateRange.resolve("last7", null, null, TODAY));
        assertEquals(new DateRange(LocalDate.of(2026, 2, 14), TODAY), DateRange.resolve("last30", null, null, TODAY));
    }

    @Test
    void monthRanges() {
        assertEquals(new DateRange(LocalDate.of(2026, 3, 1), TODAY), DateRange.resolve("thisMonth", null, null, TODAY));
        assertEquals(new DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)), DateRange.resolve("lastMonth", null, null, TODAY));
        assertEquals(new DateRange(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)),
            DateRange.resolve("lastMonth", null, null, LocalDate.of(2026, 1, 10)));
    }

    @Test
    void customRangeIsValidated() {
        assertThrows(ApiException.class, () -> DateRange.resolve("custom", null, null, TODAY));
        assertThrows(ApiException.class, () -> DateRange.resolve("custom", TODAY, TODAY.minusDays(1), TODAY));
        assertThrows(ApiException.class, () -> DateRange.resolve("custom", TODAY.minusDays(400), TODAY, TODAY));
        assertThrows(ApiException.class, () -> DateRange.resolve("nonsense", null, null, TODAY));
        assertEquals(new DateRange(TODAY.minusDays(3), TODAY), DateRange.resolve("custom", TODAY.minusDays(3), TODAY, TODAY));
    }

    @Test
    void instantsUseBusinessTimezoneAndExclusiveEnd() {
        ZoneId lagos = ZoneId.of("Africa/Lagos"); // UTC+1
        DateRange r = new DateRange(TODAY, TODAY);
        assertEquals(Instant.parse("2026-03-14T23:00:00Z"), r.start(lagos));
        assertEquals(Instant.parse("2026-03-15T23:00:00Z"), r.endExclusive(lagos));
    }
}
