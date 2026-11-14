package com.amsati.shopscale.partners;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Pure arithmetic of ShopScale Partners: no database, no Spring. */
class CommissionMathTest {
    private static final BigDecimal RATE = new BigDecimal("0.3000");
    private static final Instant T0 = Instant.parse("2026-01-31T10:00:00Z");

    private static BigDecimal c(String amount) { return CommissionMath.commission(new BigDecimal(amount), RATE, BigDecimal.ONE); }
    private static Instant plusMonths(Instant t, int m) { return t.atZone(ZoneOffset.UTC).plusMonths(m).toInstant(); }

    @Test void examplesFromTheProgrammeTerms() {
        assertEquals("1499.70", c("4999.00").toString());
        assertEquals("3599.70", c("11999.00").toString());
        assertEquals("5999.70", c("19999.00").toString());
    }

    @Test void roundsHalfUpToKobo() {
        assertEquals("1000.00", c("3333.33").toString());   // 999.999
        assertEquals(0, c("0.00").signum());
    }

    @Test void windowIsCalendarMonthsFromTheFirstPayment() {
        assertEquals(Instant.parse("2027-01-31T10:00:00Z"), CommissionMath.windowEnd(T0, 12));
    }

    @Test void twelveMonthlyPaymentsEarnAndLaterOnesDoNot() {
        Instant end = CommissionMath.windowEnd(T0, 12);
        int earned = 0;
        Instant start = T0;
        for (int i = 0; i < 14; i++) {
            Instant stop = plusMonths(T0, i + 1);
            if (CommissionMath.eligibleFraction(start, start, stop, T0, end).signum() > 0) earned++;
            start = stop;
        }
        assertEquals(12, earned);
    }

    @Test void paymentAtOrAfterWindowEndEarnsNothing() {
        Instant end = CommissionMath.windowEnd(T0, 12);
        assertEquals(0, CommissionMath.eligibleFraction(end, end, end.plusSeconds(86400 * 30), T0, end).signum());
        Instant early = end.minusSeconds(86400 * 3);   // renewal collected a few days early for a period after the window
        assertEquals(0, CommissionMath.eligibleFraction(early, end, end.plusSeconds(86400 * 30), T0, end).signum());
    }

    @Test void annualPaymentIsProratedToTheWindow() {
        Instant end = CommissionMath.windowEnd(T0, 12);
        assertEquals(0, CommissionMath.eligibleFraction(T0, T0, plusMonths(T0, 12), T0, end).compareTo(BigDecimal.ONE));
        Instant m6 = plusMonths(T0, 6);
        BigDecimal half = CommissionMath.eligibleFraction(m6, m6, plusMonths(m6, 12), T0, end);
        assertTrue(half.compareTo(new BigDecimal("0.4")) > 0 && half.compareTo(new BigDecimal("0.6")) < 0, "was " + half);
    }

    @Test void refundsReverseProportionallyAndNeverMoreThanTheCommission() {
        BigDecimal cm = new BigDecimal("1499.70"), pay = new BigDecimal("4999.00");
        assertEquals(0, CommissionMath.reversalTarget(cm, pay, pay).compareTo(cm));
        assertEquals("749.85", CommissionMath.reversalTarget(cm, pay, new BigDecimal("2499.50")).toString());
        assertEquals(0, CommissionMath.reversalTarget(cm, pay, new BigDecimal("999999")).compareTo(cm));
    }

    @Test void commissionBecomesAvailableAfterTheHoldingPeriod() {
        assertEquals(Instant.parse("2026-02-14T10:00:00Z"), CommissionMath.availableAt(T0, 14));
    }
}
