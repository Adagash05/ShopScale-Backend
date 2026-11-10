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
        return SubscriptionState.compute(s, p, Instant.now(), graceDays);
    }

    public void requireWritable(UUID businessId) {
        if (!state(businessId).writable()) {
            throw ApiException.paymentRequired("Your subscription has expired. Your data is safe, but you need to renew to record new activity.");
        }
    }

    public void requireFeature(UUID businessId, String feature, String friendlyName) {
        SubscriptionState st = state(businessId);
        if (!st.hasFeature(feature)) {
            throw ApiException.paymentRequired(friendlyName + " is not included in your " + st.planName() + " plan. Upgrade to use it.");
        }
    }

    public void requireItemCapacity(UUID businessId, long activeItemsAfter) {
        SubscriptionState st = state(businessId);
        if (st.maxItems() != null && activeItemsAfter > st.maxItems()) {
            throw ApiException.paymentRequired("Your " + st.planName() + " plan allows " + st.maxItems()
                + " active products. Archive some products or upgrade.");
        }
    }

    public void requireLocationCapacity(UUID businessId, long locationsAfter) {
        SubscriptionState st = state(businessId);
        if (locationsAfter > st.maxLocations()) {
            throw ApiException.paymentRequired("Your " + st.planName() + " plan allows " + st.maxLocations()
                + (st.maxLocations() == 1 ? " location" : " locations") + ". Upgrade to add another branch.");
        }
    }

    public void requireUserCapacity(UUID businessId, long usersAfter) {
        SubscriptionState st = state(businessId);
        if (usersAfter > st.maxUsers()) {
            throw ApiException.paymentRequired("Your " + st.planName() + " plan allows " + st.maxUsers()
                + (st.maxUsers() == 1 ? " user" : " users") + ". Remove someone or upgrade to add more people.");
        }
    }
}
