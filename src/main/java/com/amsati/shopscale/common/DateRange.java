package com.amsati.shopscale.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Inclusive local-date range resolved in the business timezone. */
public record DateRange(LocalDate from, LocalDate to) {

    public static DateRange resolve(String preset, LocalDate from, LocalDate to, LocalDate today) {
        String p = preset == null ? "today" : preset.toLowerCase();
        return switch (p) {
            case "today" -> new DateRange(today, today);
            case "yesterday" -> new DateRange(today.minusDays(1), today.minusDays(1));
            case "last7" -> new DateRange(today.minusDays(6), today);
            case "last30" -> new DateRange(today.minusDays(29), today);
            case "thismonth" -> new DateRange(today.withDayOfMonth(1), today);
            case "lastmonth" -> {
                LocalDate first = today.withDayOfMonth(1).minusMonths(1);
                yield new DateRange(first, first.withDayOfMonth(first.lengthOfMonth()));
            }
            case "custom" -> {
                if (from == null || to == null) throw ApiException.badRequest("Choose a start and end date");
                if (to.isBefore(from)) throw ApiException.badRequest("The end date must not be before the start date");
                if (ChronoUnit.DAYS.between(from, to) > 366) throw ApiException.badRequest("Choose a range of one year or less");
                yield new DateRange(from, to);
            }
            default -> throw ApiException.badRequest("Unknown date range");
        };
    }

    public Instant start(ZoneId zone) { return from.atStartOfDay(zone).toInstant(); }

    /** Exclusive upper bound. */
    public Instant endExclusive(ZoneId zone) { return to.plusDays(1).atStartOfDay(zone).toInstant(); }
}
