package com.amsati.shopscale.ask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Plain-language wording for Ask ShopScale answers. Pure functions, no Spring, so they can be checked on their own. */
public final class AskText {
    private AskText() {}

    private static DecimalFormat fmt(String pattern) { return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US)); }

    /** ₦12,500 for whole amounts and ₦12,500.50 otherwise. */
    public static String naira(BigDecimal v) {
        BigDecimal x = v == null ? BigDecimal.ZERO : v;
        boolean whole = x.stripTrailingZeros().scale() <= 0;
        return (x.signum() < 0 ? "-" : "") + "₦" + fmt(whole ? "#,##0" : "#,##0.00").format(x.abs());
    }

    /** 12.5% (one decimal, no trailing zero). */
    public static String percent(BigDecimal v) {
        if (v == null) return "";
        return fmt("#,##0.#").format(v.setScale(1, RoundingMode.HALF_UP)) + "%";
    }

    public static String count(long n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    /** "up 12.5%", "down 3%", "the same". Null when there is no honest percentage (for example nothing sold in the earlier period). */
    public static String change(BigDecimal percent) {
        if (percent == null) return null;
        if (percent.abs().compareTo(new BigDecimal("0.05")) < 0) return "the same";
        return (percent.signum() > 0 ? "up " : "down ") + percent(percent.abs());
    }

    public static String period(java.time.LocalDate from, java.time.LocalDate to) {
        if (from == null || to == null) return "";
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        return from.equals(to) ? from.format(f) : from.format(f) + " to " + to.format(f);
    }
}
