package com.amsati.shopscale.purchasing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Cost maths for receiving stock. Pure and dependency-free so it can be tested on its own. */
public final class CostMath {
    private CostMath() {}

    /**
     * Weighted-average cost after receiving stock. Stock already on hand keeps its cost, new stock brings its own,
     * so the cost of goods sold reflects what the shelf really cost. If nothing is on hand (or the count is negative
     * because overselling was allowed) the new unit cost simply applies.
     */
    public static BigDecimal weightedAverage(int onHand, BigDecimal currentCost, int received, BigDecimal unitCost) {
        return weightedAverage(BigDecimal.valueOf(onHand), currentCost, BigDecimal.valueOf(received), unitCost);
    }

    /** Same maths for decimal quantities (kilograms, litres...). */
    public static BigDecimal weightedAverage(BigDecimal onHand, BigDecimal currentCost, BigDecimal received, BigDecimal unitCost) {
        if (received.signum() <= 0) throw new IllegalArgumentException("received must be positive");
        BigDecimal cost = unitCost.setScale(2, RoundingMode.HALF_UP);
        if (onHand.signum() <= 0) return cost;
        BigDecimal have = currentCost.multiply(onHand);
        BigDecimal add = unitCost.multiply(received);
        return have.add(add).divide(onHand.add(received), 2, RoundingMode.HALF_UP);
    }
}
