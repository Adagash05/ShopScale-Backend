package com.amsati.shopscale.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AskTextTest {
    @Test void nairaShowsWholeAmountsWithoutDecimals() {
        assertEquals("₦12,500", AskText.naira(new BigDecimal("12500.00")));
        assertEquals("₦12,500.50", AskText.naira(new BigDecimal("12500.5")));
        assertEquals("₦0", AskText.naira(null));
        assertEquals("-₦300", AskText.naira(new BigDecimal("-300")));
    }
    @Test void percentUsesOneDecimalAtMost() {
        assertEquals("12.5%", AskText.percent(new BigDecimal("12.46")));
        assertEquals("3%", AskText.percent(new BigDecimal("3.0")));
    }
    @Test void changeSaysNothingWhenThereIsNoPercentage() {
        assertNull(AskText.change(null));
        assertEquals("the same", AskText.change(new BigDecimal("0.01")));
        assertEquals("up 20%", AskText.change(new BigDecimal("20")));
        assertEquals("down 5.5%", AskText.change(new BigDecimal("-5.5")));
    }
    @Test void periodWording() {
        assertEquals("3 Nov 2026", AskText.period(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 3)));
        assertEquals("1 Nov 2026 to 22 Nov 2026", AskText.period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 22)));
    }
    @Test void countAgrees() { assertEquals("1 sale", AskText.count(1, "sale", "sales")); assertEquals("0 sales", AskText.count(0, "sale", "sales")); }
}
