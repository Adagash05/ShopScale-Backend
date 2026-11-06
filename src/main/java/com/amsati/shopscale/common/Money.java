package com.amsati.shopscale.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() {}

    public static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal orZero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
