package com.amsati.shopscale.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The dashboard's period arithmetic, kept free of Spring and the database so it can be tested on its own.
 * Nothing here invents a number: when a comparison or a share cannot be calculated honestly it returns null.
 */
public final class DashboardMath {
    private DashboardMath() {}

    public record Span(LocalDate from, LocalDate to) {}
    public record Bucket(LocalDate start, LocalDate end, BigDecimal total) {}

    /** The period of the same length that ends the day before this one starts. */
    public static Span previous(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        return new Span(from.minusDays(days), from.minusDays(1));
    }

    /** Percentage change from previous to current, one decimal place. Null when there is nothing to compare with (previous missing or zero). */
    public static BigDecimal changePercent(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() <= 0) return null;
        return current.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 1, RoundingMode.HALF_UP);
    }

    /** part / total as a percentage with one decimal place. Null when the total is missing or not positive. */
    public static BigDecimal share(BigDecimal part, BigDecimal total) {
        if (part == null || total == null || total.signum() <= 0) return null;
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    /** Sums daily values into Monday-to-Sunday weeks, clipped to the days given so the first and last bucket may be partial. */
    public static List<Bucket> weekly(List<LocalDate> days, List<BigDecimal> values) {
        return bucketise(days, values, d -> d.minusDays(d.getDayOfWeek().getValue() - 1L));
    }

    /** Sums daily values into calendar months, clipped to the days given. */
    public static List<Bucket> monthly(List<LocalDate> days, List<BigDecimal> values) {
        return bucketise(days, values, d -> d.withDayOfMonth(1));
    }

    private static List<Bucket> bucketise(List<LocalDate> days, List<BigDecimal> values, Function<LocalDate, LocalDate> key) {
        List<Bucket> out = new ArrayList<>();
        LocalDate currentKey = null, start = null, end = null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < days.size(); i++) {
            LocalDate d = days.get(i);
            LocalDate k = key.apply(d);
            if (currentKey != null && !k.equals(currentKey)) {
                out.add(new Bucket(start, end, sum));
                sum = BigDecimal.ZERO; start = null;
            }
            if (start == null) start = d;
            currentKey = k; end = d;
            BigDecimal v = values.get(i);
            sum = sum.add(v == null ? BigDecimal.ZERO : v);
        }
        if (start != null) out.add(new Bucket(start, end, sum));
        return out;
    }
}
