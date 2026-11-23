package com.amsati.shopscale.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardMathTest {
    @Test
    void previousPeriodHasTheSameLengthAndEndsTheDayBefore() {
        assertEquals(new DashboardMath.Span(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 9)), DashboardMath.previous(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10)));
        assertEquals(new DashboardMath.Span(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 31)), DashboardMath.previous(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)));
    }

    @Test
    void changeIsOnlyCalculatedWhenThereIsSomethingToCompareWith() {
        assertEquals(new BigDecimal("50.0"), DashboardMath.changePercent(new BigDecimal("150"), new BigDecimal("100")));
        assertEquals(new BigDecimal("-75.0"), DashboardMath.changePercent(new BigDecimal("50"), new BigDecimal("200")));
        assertNull(DashboardMath.changePercent(new BigDecimal("50"), BigDecimal.ZERO));
        assertNull(DashboardMath.changePercent(new BigDecimal("50"), null));
    }

    @Test
    void shareIsNullForAZeroTotal() {
        assertEquals(new BigDecimal("12.5"), DashboardMath.share(new BigDecimal("25"), new BigDecimal("200")));
        assertNull(DashboardMath.share(new BigDecimal("25"), BigDecimal.ZERO));
    }

    @Test
    void weeklyBucketsStartOnMondayAndAddUpToTheDailyTotal() {
        List<LocalDate> days = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        for (int i = 0; i < 10; i++) { days.add(LocalDate.of(2026, 11, 4).plusDays(i)); values.add(BigDecimal.valueOf(i + 1)); } // Wed 4 to Fri 13 Nov
        List<DashboardMath.Bucket> weeks = DashboardMath.weekly(days, values);
        assertEquals(2, weeks.size());
        assertEquals(new BigDecimal("15"), weeks.get(0).total());
        assertEquals(new BigDecimal("40"), weeks.get(1).total());
        assertEquals(new BigDecimal("55"), weeks.stream().map(DashboardMath.Bucket::total).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void monthlyBucketsSplitAtTheMonthBoundary() {
        List<DashboardMath.Bucket> months = DashboardMath.monthly(List.of(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)), List.of(new BigDecimal("5"), new BigDecimal("7")));
        assertEquals(2, months.size());
        assertEquals(new BigDecimal("7"), months.get(1).total());
    }
}
