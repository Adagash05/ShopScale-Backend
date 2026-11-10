package com.amsati.shopscale.subscription;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.MembershipRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.notification.NotificationService;
import com.amsati.shopscale.payment.PaystackClient;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.security.TokenHash;
import com.amsati.shopscale.branch.BranchRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ShopScale's own billing, charged to the AMSATI Paystack account (PAYSTACK_SECRET_KEY).
 * A paid plan is never activated from a browser redirect: only a successful, amount-matched Paystack verification
 * (our own server-side call, triggered by the return page or by the signed webhook) activates it, exactly once.
 * Plans are prepaid for one month or one year; there is no automatic card charging, so renewal is a new checkout.
 */
@Service
@RequiredArgsConstructor
public class BillingService {
    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionPaymentRepository payments;
    private final SubscriptionService service;
    private final PaystackClient paystack;
    private final MembershipRepository memberships;
    private final BranchRepository branches;
    private final NotificationService notifications;
    private final AuditService audit;
    private final PlatformTransactionManager txm;

    @Value("${app.paystack.secret-key:}") private String secretKey;
    @Value("${app.frontend-url:http://localhost:5173}") private String frontendUrl;
    @Value("${app.subscription.grace-days:3}") private int graceDays;

    public record CheckoutRequest(String planCode, BillingCycle cycle) {}
    public record Quote(String planCode, String planName, BillingCycle cycle, BigDecimal listPrice, BigDecimal credit, BigDecimal amount, String kind) {}
    public record Checkout(String reference, String authorizationUrl, Quote quote) {}
    public record PaymentDto(UUID id, String planCode, BillingCycle cycle, String kind, BigDecimal amount, String status, String reference, Instant paidAt, Instant periodEnd, Instant createdAt, String note) {}

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }
    public boolean configured() { return secretKey != null && !secretKey.isBlank(); }
    private void needKey() { if (!configured()) throw ApiException.conflict("Online billing is not set up on this server yet (PAYSTACK_SECRET_KEY)."); }

    // ---------- pricing ----------
    public Quote quote(UUID bid, String planCode, BillingCycle cycle) {
        Plan target = plans.findById(planCode == null ? "" : planCode).filter(Plan::isActive).orElseThrow(() -> ApiException.badRequest("Choose a plan"));
        if (SubscriptionService.FREE.equals(target.getCode())) throw ApiException.badRequest("The Free plan needs no payment");
        BillingCycle c = cycle == null ? BillingCycle.MONTHLY : cycle;
        BigDecimal price = Money.scale(c == BillingCycle.ANNUAL ? target.getAnnualPrice() : target.getMonthlyPrice());
        if (price == null || price.signum() <= 0) throw ApiException.badRequest("That plan has no price for this billing period");
        Subscription cur = subscriptions.findByBusinessId(bid).orElse(null);
        Instant now = Instant.now();
        boolean paidActive = cur != null && cur.getStatus() == SubscriptionStatus.ACTIVE && now.isBefore(cur.getCurrentPeriodEnd())
            && !SubscriptionService.FREE.equals(cur.getPlanCode()) && hasPaid(bid);
        if (!paidActive) return new Quote(target.getCode(), target.getName(), c, price, BigDecimal.ZERO, price, "NEW");
        if (cur.getPlanCode().equals(target.getCode()) && cur.getBillingCycle() == c) return new Quote(target.getCode(), target.getName(), c, price, BigDecimal.ZERO, price, "RENEWAL");
        // Changing plan: unused time on the current plan is credited against the new one.
        Plan old = plans.findById(cur.getPlanCode()).orElseThrow();
        BigDecimal oldPrice = Money.scale(cur.getBillingCycle() == BillingCycle.ANNUAL ? old.getAnnualPrice() : old.getMonthlyPrice());
        long total = Math.max(1, Duration.between(cur.getCurrentPeriodStart(), cur.getCurrentPeriodEnd()).toSeconds());
        long left = Math.max(0, Duration.between(now, cur.getCurrentPeriodEnd()).toSeconds());
        BigDecimal credit = oldPrice.multiply(BigDecimal.valueOf(left)).divide(BigDecimal.valueOf(total), 2, RoundingMode.DOWN);
        BigDecimal due = price.subtract(credit);
        if (due.compareTo(new BigDecimal("100")) < 0) {
            throw ApiException.conflict("Your unused time on the current plan is worth more than the new plan for now. You can switch when your current period ends.");
        }
        return new Quote(target.getCode(), target.getName(), c, price, credit, Money.scale(due), "CHANGE");
    }

    private boolean hasPaid(UUID bid) {
        return payments.findTop50ByBusinessIdOrderByCreatedAtDesc(bid).stream().anyMatch(p -> "SUCCESS".equals(p.getStatus()));
    }

    public Quote preview(String planCode, BillingCycle cycle) {
        return quote(Tenant.businessId(), planCode, cycle);
    }

    /** A change must fit the seats and locations the target plan allows. Nothing is deleted; the owner has to free them first. */
    public void requireFits(UUID bid, Plan target) {
        long users = memberships.countByBusinessIdAndActive(bid, true);
        if (users > target.getMaxUsers()) throw ApiException.conflict("The " + target.getName() + " plan allows " + target.getMaxUsers() + (target.getMaxUsers() == 1 ? " user" : " users") + " but you have " + users + " active. Deactivate some team members first.");
        int locs = branches.findByBusinessIdOrderByMainDescNameAsc(bid).size();
        if (locs > target.getMaxLocations()) throw ApiException.conflict("The " + target.getName() + " plan allows " + target.getMaxLocations() + (target.getMaxLocations() == 1 ? " location" : " locations") + " but you have " + locs + ". Remove extra branches first.");
    }

    // ---------- checkout ----------
    public Checkout checkout(CheckoutRequest r) {
        needKey();
        UUID bid = Tenant.businessId();
        Subscription cur = subscriptions.findByBusinessId(bid).orElse(null);
        if (cur != null && cur.getStatus() == SubscriptionStatus.SUSPENDED) throw ApiException.forbidden("This account is suspended. Contact support.");
        Quote q = quote(bid, r.planCode(), r.cycle());
        requireFits(bid, plans.findById(q.planCode()).orElseThrow());
        String email = Tenant.principal().email();
        if (email == null || email.isBlank()) throw ApiException.badRequest("Your account has no email address");
        SubscriptionPayment p = new SubscriptionPayment();
        p.setBusinessId(bid);
        p.setPlanCode(q.planCode());
        p.setBillingCycle(q.cycle());
        p.setKind(q.kind());
        p.setAmount(q.amount());
        p.setListPrice(q.listPrice());
        p.setCredit(q.credit());
        p.setInitiatedBy(Tenant.userId());
        p.setReference("SSB-" + TokenHash.newToken().substring(0, 28));
        payments.save(p);
        try {
            String callback = frontendUrl.replaceAll("/+$", "") + "/more/plan?reference=" + p.getReference();
            var init = paystack.initialize(secretKey, email, PaystackClient.toKobo(q.amount()), p.getReference(), callback,
                Map.of("business_id", bid.toString(), "plan", q.planCode(), "cycle", q.cycle().name(), "kind", q.kind()));
            return new Checkout(p.getReference(), init.authorizationUrl(), q);
        } catch (RuntimeException e) {
            log.warn("Subscription checkout failed for {}: {}", bid, e.getMessage());
            p.setStatus("FAILED");
            p.setFailureNote("Could not start payment");
            payments.save(p);
            throw ApiException.conflict("We could not start the payment. Please try again.");
        }
    }

    // ---------- confirmation (return page and webhook both end here) ----------
    public SubscriptionService.Result verify(String reference) {
        needKey();
        SubscriptionPayment p = payments.findByReference(reference == null ? "" : reference).orElseThrow(() -> ApiException.notFound("Payment not found"));
        if (!p.getBusinessId().equals(Tenant.businessId())) throw ApiException.notFound("Payment not found");
        if ("PENDING".equals(p.getStatus())) settle(p.getId(), paystack.verify(secretKey, p.getReference()));
        return new SubscriptionService.Result(payments.findById(p.getId()).map(SubscriptionPayment::getStatus).orElse("PENDING"));
    }

    public void webhook(String rawBody, String signature) {
        needKey();
        if (!paystack.validSignature(secretKey, rawBody, signature)) throw ApiException.unauthorized("Bad signature");
        var root = paystack.parse(rawBody);
        String event = root.path("event").asText("");
        String ref = root.path("data").path("reference").asText("");
        if (!event.startsWith("charge.")) return;
        payments.findByReference(ref).ifPresent(p -> {
            if ("PENDING".equals(p.getStatus())) settle(p.getId(), paystack.verify(secretKey, ref));
        });
    }

    private void settle(UUID paymentId, PaystackClient.Verification v) {
        UUID[] notify = new UUID[1];
        String[] msg = new String[2];
        tx().executeWithoutResult(st -> {
            SubscriptionPayment p = payments.lockById(paymentId).orElseThrow();
            if (!"PENDING".equals(p.getStatus())) return;
            if (!v.success()) {
                if ("failed".equalsIgnoreCase(v.status()) || "abandoned".equalsIgnoreCase(v.status()) || "reversed".equalsIgnoreCase(v.status())) {
                    p.setStatus("FAILED");
                    p.setFailureNote("Paystack reported: " + v.status());
                    p.setUpdatedAt(Instant.now());
                    notify[0] = p.getBusinessId();
                    msg[0] = "Subscription payment failed";
                    msg[1] = "Your payment for the plan did not go through. No charge was applied to your plan. You can try again from the Plan page.";
                }
                return;
            }
            if (v.amountKobo() != PaystackClient.toKobo(p.getAmount()) || !"NGN".equalsIgnoreCase(v.currency()) || !Objects.equals(v.reference(), p.getReference())) {
                p.setStatus("MISMATCH");
                p.setFailureNote("Amount or currency did not match. Contact support with reference " + p.getReference());
                p.setUpdatedAt(Instant.now());
                log.error("Subscription payment {} mismatch: paid {} {} expected {}", p.getReference(), v.amountKobo(), v.currency(), p.getAmount());
                return;
            }
            Instant now = Instant.now();
            Subscription s = subscriptions.findByBusinessId(p.getBusinessId()).orElseGet(() -> { Subscription n = new Subscription(); n.setBusinessId(p.getBusinessId()); return n; });
            // Renewals stack onto time that is still left; everything else starts now.
            Instant start = now;
            if ("RENEWAL".equals(p.getKind()) && s.getCurrentPeriodEnd() != null && s.getCurrentPeriodEnd().isAfter(now)) start = s.getCurrentPeriodEnd();
            Instant end = start.atZone(ZoneOffset.UTC).plusMonths(p.getBillingCycle() == BillingCycle.ANNUAL ? 12 : 1).toInstant();
            s.setPlanCode(p.getPlanCode());
            s.setBillingCycle(p.getBillingCycle());
            s.setStatus(SubscriptionStatus.ACTIVE);
            s.setCurrentPeriodStart("RENEWAL".equals(p.getKind()) && s.getCurrentPeriodStart() != null && start.isAfter(now) ? s.getCurrentPeriodStart() : now);
            s.setCurrentPeriodEnd(end);
            s.setUpdatedAt(now);
            subscriptions.save(s);
            p.setStatus("SUCCESS");
            p.setPaidAt(now);
            p.setPeriodStart(start);
            p.setPeriodEnd(end);
            p.setUpdatedAt(now);
            audit.record(p.getBusinessId(), p.getInitiatedBy(), "SUBSCRIPTION_ACTIVATED", "subscription", s.getId(), p.getPlanCode() + " " + p.getBillingCycle() + " " + p.getReference());
            notify[0] = p.getBusinessId();
            msg[0] = "Subscription active";
            msg[1] = "Your " + p.getPlanCode() + " plan is active until " + end.atZone(ZoneOffset.UTC).toLocalDate() + ".";
        });
        if (notify[0] != null) notifications.publish(notify[0], Permission.SUBSCRIPTION_VIEW, "BILLING", msg[0], msg[1], "/plan", "billing-" + paymentId);
    }

    // ---------- history, cancel ----------
    public List<PaymentDto> history() {
        return payments.findTop50ByBusinessIdOrderByCreatedAtDesc(Tenant.businessId()).stream()
            .map(p -> new PaymentDto(p.getId(), p.getPlanCode(), p.getBillingCycle(), p.getKind(), p.getAmount(), p.getStatus(), p.getReference(), p.getPaidAt(), p.getPeriodEnd(), p.getCreatedAt(), p.getFailureNote())).toList();
    }

    /** Stops renewal reminders' implication of auto-renew and marks the plan as ending. Access continues until the paid period ends. */
    public SubscriptionService.Result cancel() {
        UUID bid = Tenant.businessId();
        tx().executeWithoutResult(st -> {
            Subscription s = subscriptions.findByBusinessId(bid).orElseThrow(() -> ApiException.notFound("No subscription"));
            if (s.getStatus() != SubscriptionStatus.ACTIVE || SubscriptionService.FREE.equals(s.getPlanCode())) throw ApiException.conflict("There is no paid plan to cancel");
            s.setStatus(SubscriptionStatus.CANCELLED);
            s.setUpdatedAt(Instant.now());
            audit.record(bid, Tenant.userId(), "SUBSCRIPTION_CANCELLED", "subscription", s.getId(), s.getPlanCode());
        });
        return new SubscriptionService.Result("CANCELLED");
    }

    // ---------- reminders ----------
    /** Daily: warn owners about plans ending within 3 days. Deduplicated per subscription and day. */
    @Scheduled(cron = "0 0 7 * * *")
    public void remindExpiring() {
        Instant now = Instant.now();
        for (Subscription s : subscriptions.findAll()) {
            if (SubscriptionService.FREE.equals(s.getPlanCode()) || s.getStatus() == SubscriptionStatus.SUSPENDED) continue;
            long days = Duration.between(now, s.getCurrentPeriodEnd()).toDays();
            if (days >= 0 && days <= 3) {
                notifications.publish(s.getBusinessId(), Permission.SUBSCRIPTION_VIEW, "BILLING", "Your plan ends soon",
                    "Your plan ends on " + s.getCurrentPeriodEnd().atZone(ZoneOffset.UTC).toLocalDate() + ". Renew to keep every feature; otherwise you move to the Free plan and your data stays safe.", "/plan",
                    "expiry-" + s.getId() + "-" + s.getCurrentPeriodEnd().atZone(ZoneOffset.UTC).toLocalDate());
            }
        }
    }
}
