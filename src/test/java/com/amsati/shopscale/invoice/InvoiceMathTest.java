package com.amsati.shopscale.invoice;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceMathTest {
    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void totalsAreComputedFromLines() {
        var t = InvoiceMath.compute(List.of(
            new InvoiceMath.LineIn(d("2"), d("1000.00"), d("100.00"), d("7.5")),    // 1900 net, 142.50 tax
            new InvoiceMath.LineIn(d("1.5"), d("333.33"), null, null)), d("50"));  // 500.00 net (499.995 -> 500.00)
        assertEquals(d("2400.00"), t.subtotal());
        assertEquals(d("142.50"), t.tax());
        assertEquals(d("100.00"), t.lineDiscounts());
        assertEquals(d("2492.50"), t.total());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> InvoiceMath.compute(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> InvoiceMath.compute(List.of(new InvoiceMath.LineIn(d("0"), d("1"), null, null)), null));
        assertThrows(IllegalArgumentException.class, () -> InvoiceMath.compute(List.of(new InvoiceMath.LineIn(d("1"), d("10"), d("11"), null)), null));
        assertThrows(IllegalArgumentException.class, () -> InvoiceMath.compute(List.of(new InvoiceMath.LineIn(d("1"), d("10"), null, d("101"))), null));
        assertThrows(IllegalArgumentException.class, () -> InvoiceMath.compute(List.of(new InvoiceMath.LineIn(d("1"), d("10"), null, null)), d("10.01")));
    }

    @Test
    void statusMovesWithPayments() {
        assertEquals("SENT", InvoiceMath.statusAfterPayment("SENT", d("100"), d("0")));
        assertEquals("PARTIALLY_PAID", InvoiceMath.statusAfterPayment("SENT", d("100"), d("40")));
        assertEquals("PARTIALLY_PAID", InvoiceMath.statusAfterPayment("DRAFT", d("100"), d("40")));
        assertEquals("PAID", InvoiceMath.statusAfterPayment("PARTIALLY_PAID", d("100"), d("100")));
        assertEquals("CANCELLED", InvoiceMath.statusAfterPayment("CANCELLED", d("100"), d("100")));
    }

    @Test
    void overdueIsDerived() {
        LocalDate today = LocalDate.of(2026, 11, 10);
        assertEquals("OVERDUE", InvoiceMath.displayStatus("SENT", d("100"), d("0"), today.minusDays(1), today));
        assertEquals("OVERDUE", InvoiceMath.displayStatus("PARTIALLY_PAID", d("100"), d("10"), today.minusDays(1), today));
        assertEquals("SENT", InvoiceMath.displayStatus("SENT", d("100"), d("0"), today, today));
        assertEquals("DRAFT", InvoiceMath.displayStatus("DRAFT", d("100"), d("0"), today.minusDays(9), today));
        assertEquals("PAID", InvoiceMath.displayStatus("PAID", d("100"), d("100"), today.minusDays(9), today));
        assertEquals("INV-000007", InvoiceMath.number(7));
    }
}
