package com.amsati.shopscale.partners;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Every commission number is decided here, from amounts the server itself recorded after Paystack verification.
 * Pure functions (no database, no Spring) so the rules can be tested exhaustively.
 *
 * Rules:
 *  - commission = amount actually collected x rate x share of the paid service period that falls inside the earning window,
 *    rounded half-up to kobo (4,999.00 x 30% = 1,499.70);
 *  - the earning window is {@code months} calendar months from the referred customer's first eligible payment;
 *  - a payment collected on or after the end of the window earns nothing, and a period that runs past the end of the window is
 *    only paid for the part inside it (so an annual payment is never counted on top of monthly ones).
 */
public final class CommissionMath {
    private CommissionMath() {}

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Instant windowEnd(Instant firstPaidAt, int months) {
        return firstPaidAt.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
    }

    public static Instant availableAt(Instant paidAt, int holdingDays) {
        return paidAt.plus(Duration.ofDays(holdingDays));
    }

    /** Share (0..1, six decimals) of the paid service period that lies inside the earning window. */
    public static BigDecimal eligibleFraction(Instant paidAt, Instant periodStart, Instant periodEnd, Instant windowStart, Instant windowEnd) {
        if (paidAt == null || windowStart == null || windowEnd == null) return BigDecimal.ZERO;
        if (!paidAt.isBefore(windowEnd)) return BigDecimal.ZERO;
        if (periodStart == null || periodEnd == null || !periodEnd.isAfter(periodStart)) return BigDecimal.ONE;
        Instant from = periodStart.isAfter(windowStart) ? periodStart : windowStart;
        Instant to = periodEnd.isBefore(windowEnd) ? periodEnd : windowEnd;
        long overlap = Duration.between(from, to).getSeconds();
        if (overlap <= 0) return BigDecimal.ZERO;
        long total = Duration.between(periodStart, periodEnd).getSeconds();
        BigDecimal f = BigDecimal.valueOf(overlap).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
        return f.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : f;
    }

    public static BigDecimal commission(BigDecimal paymentAmount, BigDecimal rate, BigDecimal fraction) {
        if (paymentAmount == null || rate == null || fraction == null || paymentAmount.signum() <= 0) return ZERO;
        return paymentAmount.multiply(rate).multiply(fraction).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * How much of a commission should be reversed in total once {@code refundedTotal} of the {@code paymentAmount} has been
     * given back: proportional, capped at the commission itself.
     */
    public static BigDecimal reversalTarget(BigDecimal commissionAmount, BigDecimal paymentAmount, BigDecimal refundedTotal) {
        if (commissionAmount == null || paymentAmount == null || paymentAmount.signum() <= 0 || refundedTotal == null || refundedTotal.signum() <= 0) return ZERO;
        BigDecimal share = refundedTotal.divide(paymentAmount, 8, RoundingMode.HALF_UP);
        if (share.compareTo(BigDecimal.ONE) >= 0) return commissionAmount.setScale(2, RoundingMode.HALF_UP);
        return commissionAmount.multiply(share).setScale(2, RoundingMode.HALF_UP).min(commissionAmount);
    }
}
