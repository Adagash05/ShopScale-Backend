package com.amsati.shopscale.subscription;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.notification.NotificationService;
import com.amsati.shopscale.payment.PaystackClient;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.SecretBox;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Automatic renewal of ShopScale subscriptions, charged to the AMSATI Paystack account (never a merchant's).
 *
 * Source of truth is PostgreSQL; nothing is kept in memory. Safe with several backend instances and across restarts:
 *  1. {@code claimDue} selects due subscriptions FOR UPDATE SKIP LOCKED, so each is handled by one instance at a time.
 *  2. A renewal attempt row and its payment row (deterministic reference) are committed BEFORE Paystack is called.
 *     UNIQUE(subscription, due date, attempt number) means an attempt, and so a charge, can be created only once.
 *  3. If the process dies after step 2, the attempt stays PENDING; {@code recoverPending} asks Paystack about that reference
 *     instead of charging again. A retry is created only after the previous attempt definitively failed.
 *  4. The subscription is extended only by {@link BillingService#settle} after Paystack's own verification matched the amount, currency, customer and plan.
 * Retries follow app.billing.retry-delays-hours inside the existing grace window (app.subscription.grace-days); attempts are never unlimited.
 */
@Service
@RequiredArgsConstructor
public class RenewalService {
    private static final Logger log = LoggerFactory.getLogger(RenewalService.class);
    /** A PENDING attempt this old is checked against Paystack instead of waiting. */
    static final Duration RECOVER_AFTER = Duration.ofMinutes(10);
    /** A charge waiting for the customer (bank authentication) is given this long before the attempt is closed. */
    static final Duration AUTH_WAIT = Duration.ofHours(24);

    private final SubscriptionRepository subscriptions;
    private final SubscriptionRenewalRepository renewals;
    private final SubscriptionPaymentRepository payments;
    private final BillingAuthorizationRepository authorizations;
    private final PlanRepository plans;
    private final PaystackClient paystack;
    private final SecretBox box;
    private final BillingService billing;
    private final NotificationService notifications;
    private final AuditService audit;
    private final PlatformTransactionManager txm;

    @Value("${app.paystack.secret-key:}") private String secretKey;
    @Value("${app.frontend-url:}") private String frontendUrl;
    @Value("${app.subscription.grace-days:3}") private int graceDays;
    @Value("${app.billing.auto-charge-enabled:true}") private boolean enabled;
    @Value("${app.billing.retry-delays-hours:24,48}") private String retryDelays;
    @Value("${app.billing.renewal-batch:25}") private int batch;

    private record Job(UUID renewalId, UUID paymentId, UUID subscriptionId, UUID businessId, String reference, String email, String authCodeEnc, long amountKobo,
                       String planCode, BillingCycle cycle, int attemptNo) {}

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }

    /** Delays between attempts, in hours. Attempt n+1 happens delays[n-1] hours after attempt n failed; none after the last. */
    List<Long> delays() {
        List<Long> out = new ArrayList<>();
        for (String x : retryDelays.split(",")) { String t = x.trim(); if (!t.isEmpty()) out.add(Long.parseLong(t)); }
        return out;
    }

    @Scheduled(initialDelayString = "${app.billing.renewal-initial-delay-ms:60000}", fixedDelayString = "${app.billing.renewal-poll-ms:900000}")
    public void scheduled() {
        try { processDue(Instant.now()); } catch (RuntimeException e) { log.error("Renewal run failed: {}", e.toString()); }
    }

    /** One scheduler pass. Public so it can be driven by tests; returns how many charges were attempted. */
    public int processDue(Instant now) {
        if (!enabled || secretKey == null || secretKey.isBlank() || !box.isConfigured()) return 0;
        recoverPending(now);
        List<Job> jobs = claim(now);
        int n = 0;
        for (Job j : jobs) { charge(j); n++; }
        return n;
    }

    // ---------- step 1+2: claim and record attempts (one transaction; Paystack is not called here) ----------
    private List<Job> claim(Instant now) {
        return tx().execute(st -> {
            List<Job> jobs = new ArrayList<>();
            for (Subscription s : subscriptions.claimDue(now, now.minus(Duration.ofDays(graceDays)), batch)) {
                Instant due = s.getCurrentPeriodEnd();
                var history = renewals.findBySubscriptionIdAndDueAtOrderByAttemptNoDesc(s.getId(), due);
                int attemptNo = 1;
                if (!history.isEmpty()) {
                    SubscriptionRenewal last = history.get(0);
                    if (!"FAILED".equals(last.getStatus()) || last.getNextRetryAt() == null || now.isBefore(last.getNextRetryAt())) continue;   // in flight, done, exhausted or not yet
                    attemptNo = last.getAttemptNo() + 1;
                }
                final int attempt = attemptNo;
                var auth = authorizations.findByBusinessIdAndActiveTrue(s.getBusinessId()).filter(BillingAuthorization::isReusable).orElse(null);
                Plan plan = plans.findById(s.getPlanCode()).filter(Plan::isActive).orElse(null);
                BigDecimal price = plan == null ? null : Money.scale(s.getBillingCycle() == BillingCycle.ANNUAL ? plan.getAnnualPrice() : plan.getMonthlyPrice());
                if (auth == null || price == null || price.signum() <= 0) {
                    // Nothing can be charged: record it once, stop automatic charging, and tell the customer what to do.
                    SubscriptionRenewal r = newAttempt(s, due, attempt, null);
                    r.setStatus("FAILED"); r.setFinishedAt(now); r.setNextRetryAt(null);
                    r.setFailureReason(auth == null ? "No saved payment method" : "The plan has no price for this billing period");
                    renewals.save(r);
                    s.setAutoRenew(false);
                    if (s.getStatus() == SubscriptionStatus.ACTIVE) s.setStatus(SubscriptionStatus.PAST_DUE);
                    s.setUpdatedAt(now);
                    notifyLater(s.getBusinessId(), "Automatic renewal could not run", "Your plan could not be renewed automatically (" + r.getFailureReason() + "). Renew it on the Plan page to keep your features.", "renew-" + s.getId() + "-" + due.getEpochSecond() + "-nomethod");
                    continue;
                }
                SubscriptionPayment p = new SubscriptionPayment();
                p.setBusinessId(s.getBusinessId());
                p.setPlanCode(s.getPlanCode());
                p.setBillingCycle(s.getBillingCycle());
                p.setKind("RENEWAL");
                p.setSource("AUTO");
                p.setAmount(price);
                p.setListPrice(price);
                p.setCustomerEmail(auth.getCustomerEmail());
                p.setReference(reference(s.getId(), due, attempt));
                payments.saveAndFlush(p);
                SubscriptionRenewal r = newAttempt(s, due, attempt, p.getId());
                renewals.saveAndFlush(r);
                jobs.add(new Job(r.getId(), p.getId(), s.getId(), s.getBusinessId(), p.getReference(), auth.getCustomerEmail(), auth.getAuthorizationCodeEnc(),
                    PaystackClient.toKobo(price), s.getPlanCode(), s.getBillingCycle(), attempt));
            }
            return jobs;
        });
    }

    private SubscriptionRenewal newAttempt(Subscription s, Instant due, int attempt, UUID paymentId) {
        SubscriptionRenewal r = new SubscriptionRenewal();
        r.setSubscriptionId(s.getId());
        r.setBusinessId(s.getBusinessId());
        r.setDueAt(due);
        r.setAttemptNo(attempt);
        r.setPaymentId(paymentId);
        return r;
    }

    /** Same subscription + period + attempt always gives the same reference, so a duplicate can never be charged under a new one. */
    static String reference(UUID subscriptionId, Instant dueAt, int attempt) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((subscriptionId + ":" + dueAt.toEpochMilli() + ":" + attempt).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("SSR-");
            for (int i = 0; i < 14; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ---------- step 3: the Paystack call and verification (outside any transaction) ----------
    private void charge(Job j) {
        PaystackClient.Charge c;
        try {
            String code = box.decrypt(j.authCodeEnc());
            c = paystack.chargeAuthorization(secretKey, j.email(), j.amountKobo(), j.reference(), code, callback(),
                Map.of("business_id", j.businessId().toString(), "plan", j.planCode(), "cycle", j.cycle().name(), "kind", "RENEWAL"));
        } catch (ApiException e) {
            if (e.getStatus() == org.springframework.http.HttpStatus.CONFLICT) {
                log.warn("Renewal charge {} outcome unknown (no answer from Paystack); it will be checked, not repeated", j.reference());
                return;   // stays PENDING; recoverPending asks Paystack about this reference
            }
            failAttempt(j, null, safe(e.getMessage()), true);
            return;
        } catch (RuntimeException e) {
            log.error("Renewal charge {} could not be prepared: {}", j.reference(), e.toString());
            failAttempt(j, null, "The saved payment method could not be read", true);
            return;
        }
        if (c.paused()) {
            // The bank wants the customer to authenticate. Nothing was charged. The attempt stays open (so no second charge is made) for AUTH_WAIT.
            tx().executeWithoutResult(st -> renewals.findById(j.renewalId()).ifPresent(r -> r.setFailureReason("Waiting for bank authentication")));
            notifyLater(j.businessId(), "Your bank needs you to confirm the renewal", "Your bank asked for extra confirmation, so your plan was not renewed automatically. Open the Plan page and pay to renew.", "renew-" + j.paymentId() + "-auth");
            return;
        }
        finish(j);
    }

    /** Verifies with Paystack and settles. Network trouble leaves the attempt PENDING for recoverPending. */
    private void finish(Job j) {
        PaystackClient.Verification v;
        try { v = paystack.verify(secretKey, j.reference()); } catch (ApiException e) { return; }
        String status = billing.settle(j.paymentId(), v);
        String reason = v.raw() == null ? null : safe(v.raw().path("gateway_response").asText(null));
        concludeAttempt(j.renewalId(), status, reason);
    }

    /** Records the result of an attempt from the payment's status. PENDING means "not decided yet" and changes nothing. */
    private void concludeAttempt(UUID renewalId, String paymentStatus, String reason) {
        if ("PENDING".equals(paymentStatus)) return;
        String[] note = new String[3];
        tx().executeWithoutResult(st -> {
            SubscriptionRenewal r = renewals.findById(renewalId).orElseThrow();
            if (!"PENDING".equals(r.getStatus())) return;
            Subscription s = subscriptions.lockById(r.getSubscriptionId()).orElseThrow();
            Instant now = Instant.now();
            r.setFinishedAt(now);
            if ("SUCCESS".equals(paymentStatus)) {
                r.setStatus("SUCCESS");
                r.setNextRetryAt(null);
                r.setFailureReason(null);
                note[0] = "Plan renewed";
                note[1] = "Your " + s.getPlanCode() + " plan was renewed automatically and is active until " + s.getCurrentPeriodEnd().atZone(ZoneOffset.UTC).toLocalDate() + ".";
                note[2] = "renew-" + r.getPaymentId() + "-ok";
                return;
            }
            SubscriptionPayment p = payments.findById(r.getPaymentId()).orElse(null);
            r.setStatus("FAILED");
            r.setFailureReason(reason != null ? reason : (p != null && p.getFailureNote() != null ? safe(p.getFailureNote()) : "The charge was not successful"));
            List<Long> d = delays();
            boolean mismatch = "MISMATCH".equals(paymentStatus);
            Instant next = (!mismatch && r.getAttemptNo() <= d.size()) ? now.plus(Duration.ofHours(d.get(r.getAttemptNo() - 1))) : null;
            // Never schedule a retry that falls outside the grace window: the plan will have expired by then.
            if (next != null && !next.isBefore(r.getDueAt().plus(Duration.ofDays(graceDays)))) next = null;
            r.setNextRetryAt(next);
            if (s.getStatus() == SubscriptionStatus.ACTIVE) s.setStatus(SubscriptionStatus.PAST_DUE);
            if (next == null) s.setAutoRenew(false);   // attempts exhausted (or mismatch): no more automatic charges until the customer acts
            s.setUpdatedAt(now);
            audit.record(s.getBusinessId(), null, "RENEWAL_FAILED", "subscription", s.getId(), "attempt " + r.getAttemptNo() + (next == null ? " final" : ""));
            note[0] = next == null ? "We could not renew your plan" : "Automatic renewal failed";
            note[1] = next == null
                ? "We tried several times and could not charge your payment method (" + r.getFailureReason() + "). Automatic renewal is now off. Open the Plan page to pay and keep your features."
                : "We could not charge your payment method (" + r.getFailureReason() + "). Your access continues for now and we will try again. You can also pay now on the Plan page.";
            note[2] = "renew-" + r.getPaymentId() + "-fail";
        });
        if (note[0] != null) {
            SubscriptionRenewal r = renewals.findById(renewalId).orElseThrow();
            notifyLater(r.getBusinessId(), note[0], note[1], note[2]);
        }
    }

    private void failAttempt(Job j, String paymentStatusOverride, String reason, boolean definite) {
        // Paystack refused the request: nothing was charged, so the payment row is closed as FAILED before the attempt is concluded.
        tx().executeWithoutResult(st -> payments.lockById(j.paymentId()).ifPresent(p -> {
            if ("PENDING".equals(p.getStatus())) { p.setStatus("FAILED"); p.setFailureNote(reason); p.setUpdatedAt(Instant.now()); }
        }));
        concludeAttempt(j.renewalId(), paymentStatusOverride == null ? "FAILED" : paymentStatusOverride, reason);
    }

    // ---------- restart / crash recovery ----------
    /** Attempts left PENDING (the process stopped, Paystack did not answer, or the bank asked for authentication) are checked, never re-charged. */
    void recoverPending(Instant now) {
        for (SubscriptionRenewal r : renewals.findByStatusAndCreatedAtBefore("PENDING", now.minus(RECOVER_AFTER))) {
            try {
                SubscriptionPayment p = payments.findById(r.getPaymentId()).orElse(null);
                if (p == null) continue;
                PaystackClient.Verification v = paystack.find(secretKey, p.getReference());
                Job j = new Job(r.getId(), p.getId(), r.getSubscriptionId(), r.getBusinessId(), p.getReference(), p.getCustomerEmail(), null, 0, p.getPlanCode(), p.getBillingCycle(), r.getAttemptNo());
                if (v == null) {
                    // Paystack has no such transaction: the charge never happened. Close it as not charged so a later attempt may run.
                    failAttempt(j, null, "The charge did not reach Paystack", true);
                } else if (v.success() || "failed".equalsIgnoreCase(v.status()) || "reversed".equalsIgnoreCase(v.status())) {
                    finish(j);
                } else if (r.getCreatedAt().isBefore(now.minus(AUTH_WAIT))) {
                    // Never completed by the customer. Closed with the "abandoned" note so a very late success is still honoured by settle().
                    tx().executeWithoutResult(st -> payments.lockById(p.getId()).ifPresent(x -> {
                        if ("PENDING".equals(x.getStatus())) { x.setStatus("FAILED"); x.setFailureNote("Paystack reported: abandoned"); x.setUpdatedAt(Instant.now()); }
                    }));
                    concludeAttempt(r.getId(), "FAILED", "The renewal was not completed");
                }
            } catch (RuntimeException e) {
                log.warn("Could not recover renewal attempt {}: {}", r.getId(), e.toString());
            }
        }
    }

    private String callback() { return frontendUrl == null || frontendUrl.isBlank() ? null : frontendUrl.replaceAll("/+$", "") + "/more/plan"; }

    private void notifyLater(UUID businessId, String title, String body, String key) {
        try { notifications.publish(businessId, Permission.SUBSCRIPTION_VIEW, "BILLING", title, body, "/more/plan", key); }
        catch (RuntimeException e) { log.warn("Could not publish billing notification: {}", e.toString()); }
    }

    /** Short, customer-safe text: no codes, no stack traces. */
    static String safe(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.replaceAll("[\\r\\n]+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
