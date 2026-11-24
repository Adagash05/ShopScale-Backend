package com.amsati.shopscale.subscription;

import com.amsati.shopscale.common.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubscriptionService {
    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Value("${app.subscription.trial-plan:BUSINESS}")
    private String trialPlan;
    @Value("${app.subscription.trial-days:14}")
    private int trialDays;
    @Value("${app.subscription.grace-days:3}")
    private int graceDays;

    @Transactional
    public Subscription startTrial(UUID businessId) {
        Plan plan = plans.findById(trialPlan).orElseThrow(() -> new IllegalStateException("Trial plan missing: " + trialPlan));
        Instant now = Instant.now();
        Subscription s = new Subscription();
        s.setBusinessId(businessId);
        s.setPlanCode(plan.getCode());
        s.setBillingCycle(BillingCycle.MONTHLY);
        s.setStatus(SubscriptionStatus.TRIALING);
        s.setCurrentPeriodStart(now);
        s.setCurrentPeriodEnd(now.plus(Duration.ofDays(trialDays)));
        return subscriptions.save(s);
    }

    @Transactional(readOnly = true)
    public SubscriptionState state(UUID businessId) {
        Subscription s = subscriptions.findByBusinessId(businessId)
            .orElseThrow(() -> ApiException.paymentRequired("This business has no subscription"));
        Plan p = plans.findById(s.getPlanCode()).orElseThrow(() -> new IllegalStateException("Plan missing: " + s.getPlanCode()));
        SubscriptionState st = SubscriptionState.compute(s, p, Instant.now(), graceDays);
        // A lapsed trial or paid plan falls back to the permanent Free plan instead of locking the business out.
        if ("EXPIRED".equals(st.status()) && s.getStatus() != SubscriptionStatus.SUSPENDED && !FREE.equals(p.getCode())) {
            return plans.findById(FREE).map(f -> SubscriptionState.freeFallback(st, f)).orElse(st);
        }
        return st;
    }

    public static final String FREE = "FREE";
    public record Result(String status) {}

    /** Move a business to the permanent Free plan now. No payment is needed and no data is removed; limits only stop new additions. */
    @Transactional
    public Subscription chooseFree(UUID businessId) {
        Plan free = plans.findById(FREE).orElseThrow(() -> new IllegalStateException("Free plan missing"));
        Subscription s = subscriptions.findByBusinessId(businessId).orElseGet(() -> { Subscription n = new Subscription(); n.setBusinessId(businessId); return n; });
        if (s.getStatus() == SubscriptionStatus.SUSPENDED) throw ApiException.forbidden("This account is suspended. Contact support.");
        Instant now = Instant.now();
        s.setPlanCode(free.getCode());
        s.setBillingCycle(BillingCycle.MONTHLY);
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setCurrentPeriodStart(now);
        s.setCurrentPeriodEnd(java.time.Instant.parse("2099-12-31T00:00:00Z"));
        s.setUpdatedAt(now);
        return subscriptions.save(s);
    }

    public void requireWritable(UUID businessId) {
        if (!state(businessId).writable()) {
            throw ApiException.paymentRequired("Your subscription has expired. Your data is safe, but you need to renew to record new activity.");
        }
    }

    public void requireFeature(UUID businessId, String feature, String friendlyName) {
        SubscriptionState st = state(businessId);
        if (!st.hasFeature(feature)) {
            throw ApiException.planLimit("FEATURE_NOT_IN_PLAN", friendlyName + " is not included in your " + st.planName() + " plan. Upgrade to use it.",
                java.util.Map.of("feature", feature, "plan", st.planCode()));
        }
    }

    /**
     * Serialises "check the allowance, then create" for one business and one kind of record until the surrounding transaction ends,
     * so two requests at the same moment cannot both squeeze under the limit. Must be called inside the creating @Transactional method,
     * before the count is read.
     */
    public void lockForCreate(UUID businessId, String resource) {
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) t", Integer.class, businessId + ":limit:" + resource);
    }

    private ApiException limit(SubscriptionState st, String resource, Integer allowed, String message) {
        java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("resource", resource);
        d.put("limit", allowed);
        d.put("plan", st.planCode());
        return ApiException.planLimit("PLAN_LIMIT_REACHED", message, d);
    }

    public void requireItemCapacity(UUID businessId, long activeItemsAfter) {
        SubscriptionState st = state(businessId);
        if (UsagePeriod.exceeds(st.maxItems(), activeItemsAfter)) {
            throw limit(st, "products", st.maxItems(), "Your " + st.planName() + " plan allows " + st.maxItems()
                + " active products. Archive some products or upgrade.");
        }
    }

    public void requireCustomerCapacity(UUID businessId, long customersAfter) {
        SubscriptionState st = state(businessId);
        if (UsagePeriod.exceeds(st.maxCustomers(), customersAfter)) {
            throw limit(st, "customers", st.maxCustomers(), "Your " + st.planName() + " plan allows " + st.maxCustomers() + " customer records. Upgrade to add more customers.");
        }
    }

    public void requireSupplierCapacity(UUID businessId, long suppliersAfter) {
        SubscriptionState st = state(businessId);
        if (UsagePeriod.exceeds(st.maxSuppliers(), suppliersAfter)) {
            throw limit(st, "suppliers", st.maxSuppliers(), st.maxSuppliers() == 0
                ? "Supplier records are not included in your " + st.planName() + " plan. Upgrade to add suppliers."
                : "Your " + st.planName() + " plan allows " + st.maxSuppliers() + " suppliers. Upgrade to add more suppliers.");
        }
    }

    /** The window expense records are counted in: all time (TOTAL) or the current billing month. Null start = all time. */
    public UsagePeriod.Window expenseWindow(SubscriptionState st) {
        return "MONTH".equals(st.expensePeriod()) ? UsagePeriod.billingMonth(st.periodStart(), Instant.now()) : null;
    }

    /** @param countInWindow how many expenses the business already has in the given window (null window = all of them). */
    public void requireExpenseCapacity(UUID businessId, java.util.function.Function<UsagePeriod.Window, Long> countInWindow) {
        SubscriptionState st = state(businessId);
        if (st.maxExpenses() == null) return;
        long after = countInWindow.apply(expenseWindow(st)) + 1;
        if (UsagePeriod.exceeds(st.maxExpenses(), after)) {
            throw limit(st, "expenses", st.maxExpenses(), "MONTH".equals(st.expensePeriod())
                ? "Your " + st.planName() + " plan allows " + st.maxExpenses() + " expense records per billing month. Upgrade to record more, or wait for your next billing month."
                : "Your " + st.planName() + " plan allows " + st.maxExpenses() + " expense records. Upgrade to record more expenses.");
        }
    }

    public void requireLocationCapacity(UUID businessId, long locationsAfter) {
        SubscriptionState st = state(businessId);
        if (locationsAfter > st.maxLocations()) {
            throw limit(st, "locations", st.maxLocations(), "Your " + st.planName() + " plan allows " + st.maxLocations()
                + (st.maxLocations() == 1 ? " location" : " locations") + ". Upgrade to add another branch.");
        }
    }

    public void requireUserCapacity(UUID businessId, long usersAfter) {
        SubscriptionState st = state(businessId);
        if (usersAfter > st.maxUsers()) {
            throw limit(st, "users", st.maxUsers(), "Your " + st.planName() + " plan allows " + st.maxUsers()
                + (st.maxUsers() == 1 ? " user" : " users") + ". Remove someone or upgrade to add more people.");
        }
    }
}
