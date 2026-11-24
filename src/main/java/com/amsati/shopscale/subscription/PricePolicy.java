package com.amsati.shopscale.subscription;

import com.amsati.shopscale.common.Money;
import java.math.BigDecimal;

/** Which price a subscriber pays: the plan's list price, unless they hold an agreed price for exactly this plan and billing cycle. Pure, so it is tested without a database. */
public final class PricePolicy {
    private PricePolicy() {}

    public static BigDecimal effective(String planCode, BillingCycle cycle, BigDecimal monthlyList, BigDecimal annualList,
                                       String lockedPlanCode, BillingCycle lockedCycle, BigDecimal lockedPrice) {
        boolean agreed = lockedPrice != null && lockedPrice.signum() > 0 && planCode != null && planCode.equals(lockedPlanCode) && lockedCycle != null && lockedCycle == cycle;
        if (agreed) return Money.scale(lockedPrice);
        return Money.scale(cycle == BillingCycle.ANNUAL ? annualList : monthlyList);
    }
}
