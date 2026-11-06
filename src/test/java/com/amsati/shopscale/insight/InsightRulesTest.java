package com.amsati.shopscale.insight;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InsightRulesTest {
    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void stockCoverNeedsEnoughSalesToTrustTheRate() {
        assertEquals(10.0, InsightRules.daysOfCover(2, 6, 30), 1e-9);
        assertNull(InsightRules.daysOfCover(2, 2, 30));
    }

    @Test
    void flagsOnlyProductsThatWillRunOutWithinAWeek() {
        assertTrue(InsightRules.runningOutSoon(1, 30, 30));
        assertFalse(InsightRules.runningOutSoon(100, 30, 30));
        assertFalse(InsightRules.runningOutSoon(0, 30, 30));   // already out: reported separately
    }

    @Test
    void reorderSuggestionCoversTwoWeeksAndIsNeverNegative() {
        assertEquals(10, InsightRules.suggestedReorder(4, 30, 30));
        assertEquals(0, InsightRules.suggestedReorder(500, 30, 30));
        assertEquals(0, InsightRules.suggestedReorder(0, 0, 30));
    }

    @Test
    void marginRules() {
        assertEquals(d("10.0"), InsightRules.marginPercent(d("100"), d("90")));
        assertTrue(InsightRules.weakMargin(d("100"), d("95")));
        assertFalse(InsightRules.weakMargin(d("100"), d("90")));
        assertTrue(InsightRules.weakMargin(d("100"), d("120")));   // selling below cost
        assertNull(InsightRules.marginPercent(d("100"), d("0")));   // no cost entered
    }

    @Test
    void changeNeedsSomethingToCompareWith() {
        assertEquals(30.0, InsightRules.changePercent(d("100"), d("130")));
        assertEquals(-25.0, InsightRules.changePercent(d("200"), d("150")));
        assertNull(InsightRules.changePercent(d("0"), d("50")));
    }

    @Test
    void overdueAfterTwoWeeksOfSilence() {
        Instant now = Instant.parse("2026-06-30T00:00:00Z");
        assertTrue(InsightRules.isOverdue(now.minus(Duration.ofDays(15)), now));
        assertFalse(InsightRules.isOverdue(now.minus(Duration.ofDays(14)), now));
        assertFalse(InsightRules.isOverdue(null, now));
    }
}
