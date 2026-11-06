package com.amsati.shopscale.purchasing;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CostMathTest {
    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void blendsOldAndNewStock() {
        assertEquals(d("150.00"), CostMath.weightedAverage(10, d("100"), 10, d("200")));
        assertEquals(d("10.25"), CostMath.weightedAverage(3, d("10"), 1, d("11")));
    }

    @Test
    void emptyOrNegativeShelfTakesTheNewCost() {
        assertEquals(d("120.50"), CostMath.weightedAverage(0, d("100"), 5, d("120.5")));
        assertEquals(d("90.00"), CostMath.weightedAverage(-3, d("100"), 5, d("90")));
    }

    @Test
    void rejectsNothingReceived() {
        assertThrows(IllegalArgumentException.class, () -> CostMath.weightedAverage(1, d("1"), 0, d("1")));
    }
}
