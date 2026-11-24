package com.amsati.shopscale.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PricePolicyTest {
    static final BigDecimal M = new BigDecimal("14999"), A = new BigDecimal("149990");
    static void is(String want, BigDecimal got) { assertEquals(0, new BigDecimal(want).compareTo(got), "wanted " + want + " got " + got); }

    @Test void newSubscribersPayTheListPrice() {
        is("14999", PricePolicy.effective("GROWTH", BillingCycle.MONTHLY, M, A, null, null, null));
        is("149990", PricePolicy.effective("GROWTH", BillingCycle.ANNUAL, M, A, null, null, null));
    }
    @Test void existingSubscribersKeepTheirAgreedPrice() {
        is("11999", PricePolicy.effective("GROWTH", BillingCycle.MONTHLY, M, A, "GROWTH", BillingCycle.MONTHLY, new BigDecimal("11999")));
        is("119990", PricePolicy.effective("GROWTH", BillingCycle.ANNUAL, M, A, "GROWTH", BillingCycle.ANNUAL, new BigDecimal("119990")));
    }
    @Test void theAgreedPriceEndsWhenPlanOrCycleChanges() {
        is("149990", PricePolicy.effective("GROWTH", BillingCycle.ANNUAL, M, A, "GROWTH", BillingCycle.MONTHLY, new BigDecimal("11999")));
        is("24999", PricePolicy.effective("BUSINESS", BillingCycle.MONTHLY, new BigDecimal("24999"), new BigDecimal("249990"), "GROWTH", BillingCycle.MONTHLY, new BigDecimal("11999")));
        is("14999", PricePolicy.effective("GROWTH", BillingCycle.MONTHLY, M, A, "GROWTH", BillingCycle.MONTHLY, BigDecimal.ZERO));
    }
}
