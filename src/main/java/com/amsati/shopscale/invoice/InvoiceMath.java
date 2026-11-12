package com.amsati.shopscale.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Invoice arithmetic. Pure functions so the rules can be tested without a database. The server always recomputes. */
public final class InvoiceMath {
    private InvoiceMath() {}

    public record LineIn(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal taxRate) {}
    public record LineOut(BigDecimal net, BigDecimal tax) {}
    /** total = subtotal (sum of line amounts after line discounts) + tax - invoiceDiscount. */
    public record Totals(List<LineOut> lines, BigDecimal subtotal, BigDecimal lineDiscounts, BigDecimal tax, BigDecimal invoiceDiscount, BigDecimal total) {}

    public static BigDecimal money(BigDecimal v) { return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP); }

    public static Totals compute(List<LineIn> lines, BigDecimal invoiceDiscount) {
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("Add at least one item");
        List<LineOut> out = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO, discounts = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (LineIn l : lines) {
            if (l.quantity() == null || l.quantity().signum() <= 0) throw new IllegalArgumentException("Quantity must be above zero");
            if (l.unitPrice() == null || l.unitPrice().signum() < 0) throw new IllegalArgumentException("Price cannot be negative");
            BigDecimal gross = money(l.quantity().multiply(l.unitPrice()));
            BigDecimal disc = money(l.discount());
            if (disc.signum() < 0) throw new IllegalArgumentException("Discount cannot be negative");
            if (disc.compareTo(gross) > 0) throw new IllegalArgumentException("A line discount is more than the line amount");
            BigDecimal rate = l.taxRate() == null ? BigDecimal.ZERO : l.taxRate();
            if (rate.signum() < 0 || rate.compareTo(new BigDecimal("100")) > 0) throw new IllegalArgumentException("Tax rate must be between 0 and 100");
            BigDecimal net = gross.subtract(disc);
            BigDecimal t = money(net.multiply(rate).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            out.add(new LineOut(net, t));
            subtotal = subtotal.add(net);
            discounts = discounts.add(disc);
            tax = tax.add(t);
        }
        BigDecimal inv = money(invoiceDiscount);
        if (inv.signum() < 0) throw new IllegalArgumentException("Discount cannot be negative");
        BigDecimal beforeDiscount = subtotal.add(tax);
        if (inv.compareTo(beforeDiscount) > 0) throw new IllegalArgumentException("The discount is more than the invoice total");
        return new Totals(out, subtotal, discounts, tax, inv, beforeDiscount.subtract(inv));
    }

    /** Stored status after money changes. DRAFT and SENT only move to PARTIALLY_PAID / PAID through payments. */
    public static String statusAfterPayment(String current, BigDecimal total, BigDecimal paid) {
        if ("CANCELLED".equals(current)) return current;
        if (total.signum() > 0 && paid.compareTo(total) >= 0) return "PAID";
        if (paid.signum() > 0) return "PARTIALLY_PAID";
        return current;
    }

    /** What the person sees: OVERDUE is derived, never stored, so it cannot go stale. */
    public static String displayStatus(String stored, BigDecimal total, BigDecimal paid, LocalDate due, LocalDate today) {
        boolean open = "SENT".equals(stored) || "PARTIALLY_PAID".equals(stored);
        if (open && due != null && due.isBefore(today) && total.subtract(paid).signum() > 0) return "OVERDUE";
        return stored;
    }

    public static String number(long n) { return String.format("INV-%06d", n); }
}
