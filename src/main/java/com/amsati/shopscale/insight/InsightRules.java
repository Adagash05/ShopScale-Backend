package com.amsati.shopscale.insight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * The transparent rules behind every dashboard insight. Plain arithmetic on the business's own records, with the
 * thresholds named here and repeated in the explanation shown to the user. Nothing is predicted or guessed.
 */
public final class InsightRules {
    /** Look-back window used to measure how fast something sells. */
    public static final int SALES_WINDOW_DAYS = 30;
    /** Warn when current stock covers this many days or fewer at the recent selling rate. */
    public static final int STOCKOUT_HORIZON_DAYS = 7;
    /** Needs at least this many units sold in the window before a selling rate is trusted. */
    public static final int MIN_UNITS_FOR_RATE = 3;
    /** A reorder suggestion aims to cover this many days of selling. */
    public static final int REORDER_COVER_DAYS = 14;
    /** Gross margin below this percentage is flagged as weak. */
    public static final int WEAK_MARGIN_PERCENT = 10;
    /** A customer who owes money and has had no sale on credit or payment for this long is flagged. */
    public static final int OVERDUE_DAYS = 14;
    /** Period-on-period comparisons only run for ranges at least this long, because single days are too noisy. */
    public static final int MIN_COMPARE_DAYS = 7;
    public static final int EXPENSE_RISE_PERCENT = 30;
    public static final int SALES_DROP_PERCENT = 20;

    private InsightRules() {}

    /** Days the current stock will last at the recent rate, or null if there is no reliable rate. */
    public static Double daysOfCover(int stock, long unitsSold, int windowDays) {
        if (unitsSold < MIN_UNITS_FOR_RATE || windowDays <= 0) return null;
        double perDay = (double) unitsSold / windowDays;
        return Math.max(stock, 0) / perDay;
    }

    public static boolean runningOutSoon(int stock, long unitsSold, int windowDays) {
        Double days = daysOfCover(stock, unitsSold, windowDays);
        return days != null && stock > 0 && days <= STOCKOUT_HORIZON_DAYS;
    }

    /** Units to buy so stock covers REORDER_COVER_DAYS of selling. */
    public static int suggestedReorder(int stock, long unitsSold, int windowDays) {
        if (unitsSold <= 0 || windowDays <= 0) return 0;
        double perDay = (double) unitsSold / windowDays;
        return Math.max(0, (int) Math.ceil(perDay * REORDER_COVER_DAYS) - Math.max(stock, 0));
    }

    /** Gross margin as a percentage of price, or null if price or cost is missing. */
    public static BigDecimal marginPercent(BigDecimal price, BigDecimal cost) {
        if (price == null || cost == null || price.signum() <= 0 || cost.signum() <= 0) return null;
        return price.subtract(cost).multiply(BigDecimal.valueOf(100)).divide(price, 1, RoundingMode.HALF_UP);
    }

    public static boolean weakMargin(BigDecimal price, BigDecimal cost) {
        BigDecimal m = marginPercent(price, cost);
        return m != null && m.compareTo(BigDecimal.valueOf(WEAK_MARGIN_PERCENT)) < 0;
    }

    /** Percentage change from previous to current, or null when there is no previous figure to compare with. */
    public static Double changePercent(BigDecimal previous, BigDecimal current) {
        if (previous == null || previous.signum() <= 0 || current == null) return null;
        return current.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 1, RoundingMode.HALF_UP).doubleValue();
    }

    public static boolean isOverdue(Instant lastActivity, Instant now) {
        return lastActivity != null && lastActivity.isBefore(now.minus(Duration.ofDays(OVERDUE_DAYS)));
    }
}
