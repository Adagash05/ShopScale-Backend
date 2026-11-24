package com.amsati.shopscale.subscription;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.MembershipRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.notification.NotificationService;
import com.amsati.shopscale.payment.PaystackClient;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.security.SecretBox;
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
 * Plans are prepaid for one month or one year and ALWAYS renew automatically: every paid checkout saves the card's reusable Paystack
 * authorization (encrypted) and turns automatic renewal on; {@link RenewalService} then charges it when each period ends, until the
 * customer cancels. There is no one-time option and no opt-in. Cancelling stops future charges and keeps access until the paid period ends.
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
    private final BillingAuthorizationRepository authorizations;
    private final SubscriptionRenewalRepository renewals;
    private final SecretBox box;
    private final com.amsati.shopscale.partners.CommissionService affiliateCommissions;

    @Value("${app.paystack.secret-key:}") private String secretKey;
    @Value("${app.frontend-url:}") private String frontendUrl;
    @Value("${app.subscription.grace-days:3}") private int graceDays;

    /** Plan and billing period only. Automatic renewal is not a choice, so older clients that still send autoRenew/consent have those fields ignored. */
    public record CheckoutRequest(String planCode, BillingCycle cycle) {}
    public record Quote(String planCode, String planName, BillingCycle cycle, BigDecimal listPrice, BigDecimal credit, BigDecimal amount, String kind) {}
    public record Checkout(String reference, String authorizationUrl, Quote quote) {}
    public record PaymentDto(UUID id, String planCode, BillingCycle cycle, String kind, BigDecimal amount, String status, String reference, Instant paidAt, Instant periodEnd, Instant createdAt, String note, String source) {}
    /** What the customer may see about the saved payment method. The authorization code is never included. */
    public record MethodDto(String brand, String last4, String expMonth, String expYear, String bank, String channel) {}
    public record RenewalDto(String status, int attemptNo, Instant dueAt, Instant nextRetryAt, String failureReason) {}
    public record Overview(boolean autoRenew, boolean autoRenewAvailable, Instant nextRenewalAt, BigDecimal nextRenewalAmount, String nextRenewalPlan, BillingCycle cycle,
                           MethodDto paymentMethod, RenewalDto lastRenewal, Instant cancelledAt, Instant consentAt, PaymentDto lastPayment, int graceDays) {}

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }
    public boolean configured() { return secretKey != null && !secretKey.isBlank(); }
    /** What an owner or operator can see about billing setup. Booleans and the key mode only: never a key, never part of one. */
    public Map<String, Object> configStatus() {
        String k = secretKey == null ? "" : secretKey.trim();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("configured", !k.isBlank());
        m.put("keyLooksValid", k.startsWith("sk_test_") || k.startsWith("sk_live_"));
        m.put("mode", k.startsWith("sk_live_") ? "live" : k.startsWith("sk_test_") ? "test" : null);
        m.put("encryptionConfigured", box.isConfigured());
        m.put("encryptionKeySource", box.source().name());   // ENVIRONMENT, LOCAL_FILE (development only) or NONE
        m.put("returnUrlConfigured", frontendUrl != null && !frontendUrl.isBlank());
        return m;
    }

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
        boolean paidActive = cur != null && (cur.getStatus() == SubscriptionStatus.ACTIVE || cur.getStatus() == SubscriptionStatus.CANCELLED) && now.isBefore(cur.getCurrentPeriodEnd())
            && !SubscriptionService.FREE.equals(cur.getPlanCode()) && hasPaid(bid);
        if (!paidActive) return new Quote(target.getCode(), target.getName(), c, price, BigDecimal.ZERO, price, "NEW");
        if (cur.getPlanCode().equals(target.getCode()) && cur.getBillingCycle() == c) {
            BigDecimal mine = cur.effectivePrice(target);   // a subscriber with an agreed price renews at it
            return new Quote(target.getCode(), target.getName(), c, mine, BigDecimal.ZERO, mine, "RENEWAL");
        }
        // Changing plan: unused time on the current plan is credited against the new one.
        Plan old = plans.findById(cur.getPlanCode()).orElseThrow();
        BigDecimal oldPrice = cur.effectivePrice(old);   // credit what they actually pay
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
        if (frontendUrl == null || frontendUrl.isBlank()) throw ApiException.conflict("Online billing is not set up on this server yet (FRONTEND_URL).");
        // Subscriptions renew automatically, so a payment that could not be renewed later is never started.
        if (!box.isConfigured()) throw ApiException.conflict("Subscription billing is not fully set up on this server yet (APP_ENCRYPTION_KEY), so payment cannot be started.");
        SubscriptionPayment p = new SubscriptionPayment();
        p.setBusinessId(bid);
        p.setPlanCode(q.planCode());
        p.setBillingCycle(q.cycle());
        p.setKind(q.kind());
        p.setAmount(q.amount());
        p.setListPrice(q.listPrice());
        p.setCredit(q.credit());
        p.setInitiatedBy(Tenant.userId());
        p.setCustomerEmail(email.trim());
        p.setAutoRenewConsent(true);   // automatic renewal is the terms of every paid plan, disclosed on the checkout screen
        p.setConsentAt(Instant.now());
        p.setReference("SSB-" + TokenHash.newToken().substring(0, 28));
        payments.save(p);
        try {
            String callback = frontendUrl.replaceAll("/+$", "") + "/more/plan?reference=" + p.getReference();
            var init = paystack.initialize(secretKey, email, PaystackClient.toKobo(q.amount()), p.getReference(), callback,
                Map.of("business_id", bid.toString(), "plan", q.planCode(), "cycle", q.cycle().name(), "kind", q.kind()), List.of("card"));
            if (init.authorizationUrl() == null || !init.authorizationUrl().startsWith("https://")) throw new IllegalStateException("Paystack returned no checkout address");
            return new Checkout(p.getReference(), init.authorizationUrl(), q);
        } catch (RuntimeException e) {
            // The reason is logged in full (never the key) so a failure can be diagnosed; the customer gets the safe part of it.
            log.error("Subscription checkout failed: business={} reference={} plan={} cause={}: {}", bid, p.getReference(), q.planCode(), e.getClass().getSimpleName(), e.getMessage());
            p.setStatus("FAILED");
            p.setFailureNote(trim(e instanceof ApiException ? e.getMessage() : "Could not start payment", 200));
            p.setUpdatedAt(Instant.now());
            payments.save(p);
            if (e instanceof ApiException ae && ae.getMessage() != null && ae.getMessage().startsWith("Paystack did not accept")) {
                throw ApiException.conflict("Online payment is not working on our side right now (the payment provider key was refused). Please contact ShopScale support.");
            }
            if (e instanceof ApiException ae && ae.getStatus() == org.springframework.http.HttpStatus.CONFLICT) throw ae;   // "Could not reach Paystack": say so
            throw ApiException.conflict("We could not start the payment. Please try again.");
        }
    }

    // ---------- confirmation (return page and webhook both end here) ----------
    public SubscriptionService.Result verify(String reference) {
        needKey();
        SubscriptionPayment p = payments.findByReference(reference == null ? "" : reference).orElseThrow(() -> ApiException.notFound("Payment not found"));
        if (!p.getBusinessId().equals(Tenant.businessId())) throw ApiException.notFound("Payment not found");
        if (settleable(p)) settle(p.getId(), paystack.verify(secretKey, p.getReference()));
        return new SubscriptionService.Result(payments.findById(p.getId()).map(SubscriptionPayment::getStatus).orElse("PENDING"));
    }

    /** The signature is computed over the exact bytes Paystack sent. A failure while confirming throws, so Paystack retries the delivery. */
    public void webhook(byte[] rawBody, String signature) {
        needKey();
        if (!paystack.validSignature(secretKey, rawBody, signature)) throw ApiException.unauthorized("Bad signature");
        var root = paystack.parse(rawBody);
        String event = root.path("event").asText("");
        String ref = root.path("data").path("reference").asText("");
        if (event.equals("refund.processed") || event.equals("charge.dispute.resolve")) { partnerRefund(event, root.path("data")); return; }
        if (!event.startsWith("charge.")) return;
        payments.findByReference(ref).ifPresent(p -> {
            if (settleable(p)) settle(p.getId(), paystack.verify(secretKey, ref));
        });
    }

    /**
     * A refund, or a chargeback decided in the customer's favour, reverses the matching partner commission. The notice is already
     * signature-checked. Each refund is applied once (its reference is recorded). Other dispute stages change nothing.
     */
    private void partnerRefund(String event, com.fasterxml.jackson.databind.JsonNode d) {
        String ref; String refundRef; long kobo; String source;
        if (event.equals("refund.processed")) {
            ref = d.path("transaction_reference").asText("");
            if (ref.isBlank()) ref = d.path("transaction").path("reference").asText("");
            refundRef = "paystack-refund:" + d.path("id").asText(d.path("refund_reference").asText(""));
            kobo = d.path("amount").asLong(0);
            source = "WEBHOOK";
        } else {
            String resolution = d.path("resolution").asText("");
            if (!resolution.equals("merchant-accepted") && !resolution.equals("accepted")) return;
            ref = d.path("transaction").path("reference").asText("");
            refundRef = "paystack-dispute:" + d.path("id").asText("");
            kobo = d.path("refund_amount").asLong(0);
            source = "CHARGEBACK";
        }
        if (ref.isBlank() || refundRef.endsWith(":")) { log.warn("Paystack {} notice without a usable reference was ignored", event); return; }
        final String payRef = ref;
        payments.findByReference(payRef).ifPresent(p -> {
            java.math.BigDecimal amount = kobo > 0 ? java.math.BigDecimal.valueOf(kobo).movePointLeft(2) : p.getAmount();   // a dispute notice without an amount means the whole payment
            affiliateCommissions.applyRefund(p.getId(), refundRef, source, amount);
        });
    }

    /**
     * A payment can be settled while PENDING. A row an earlier version of this code marked FAILED only because Paystack said
     * "abandoned" (the customer had not finished paying yet) is also re-checked, so money that arrives later is not lost.
     */
    private static boolean settleable(SubscriptionPayment p) {
        return "PENDING".equals(p.getStatus()) || ("FAILED".equals(p.getStatus()) && p.getFailureNote() != null && p.getFailureNote().equals("Paystack reported: abandoned"));
    }

    /**
     * Settles one payment from a Paystack verification, atomically: the payment row is locked, the payment and the subscription change
     * in one database transaction, and a payment that is no longer settleable is left alone (so the return page, the webhook and the
     * scheduler can race safely). Returns the payment's status afterwards.
     */
    String settle(UUID paymentId, PaystackClient.Verification v) {
        UUID[] notify = new UUID[1];
        String[] msg = new String[2];
        tx().executeWithoutResult(st -> {
            SubscriptionPayment p = payments.lockById(paymentId).orElseThrow();
            if (!settleable(p)) return;
            boolean auto = "AUTO".equals(p.getSource());
            if (!v.success()) {
                // Only a definite failure ends a payment. "abandoned", "pending", "ongoing" etc. mean the customer may still pay.
                if ("failed".equalsIgnoreCase(v.status()) || "reversed".equalsIgnoreCase(v.status())) {
                    p.setStatus("FAILED");
                    p.setFailureNote("Paystack reported: " + v.status());
                    p.setUpdatedAt(Instant.now());
                    if (!auto) {
                        notify[0] = p.getBusinessId();
                        msg[0] = "Subscription payment failed";
                        msg[1] = "Your payment for the plan did not go through. No charge was applied to your plan. You can try again from the Plan page.";
                    }
                }
                return;
            }
            String mismatch = mismatch(p, v);
            if (mismatch != null) {
                p.setStatus("MISMATCH");
                p.setFailureNote("Payment details did not match (" + mismatch + "). Contact support with reference " + p.getReference());
                p.setUpdatedAt(Instant.now());
                log.error("Subscription payment {} mismatch: {}", p.getReference(), mismatch);
                return;
            }
            Instant now = Instant.now();
            Subscription s = subscriptions.findByBusinessId(p.getBusinessId()).orElseGet(() -> { Subscription n = new Subscription(); n.setBusinessId(p.getBusinessId()); return n; });
            boolean renewal = "RENEWAL".equals(p.getKind());
            Instant oldEnd = s.getCurrentPeriodEnd();
            // A renewal continues from the end of the period it renews. Paid early, that is in the future; charged automatically it may be
            // a little in the past (retries run inside the grace window), and the new period still starts where the old one ended.
            Instant start = now;
            if (renewal && oldEnd != null && (oldEnd.isAfter(now) || (auto && oldEnd.plus(Duration.ofDays(graceDays)).isAfter(now)))) start = oldEnd;
            Instant anchor = !renewal ? start : (s.getBillingAnchor() != null ? s.getBillingAnchor() : (s.getCurrentPeriodStart() != null ? s.getCurrentPeriodStart() : start));
            Instant end = RenewalDates.periodEnd(start, anchor, p.getBillingCycle());
            s.setPlanCode(p.getPlanCode());
            s.setBillingCycle(p.getBillingCycle());
            if (!renewal) s.clearLockedPrice();   // a new purchase or a plan/cycle change is at the current list price; a renewal keeps the agreed price
            s.setStatus(SubscriptionStatus.ACTIVE);
            s.setCurrentPeriodStart(renewal && start.isAfter(now) && s.getCurrentPeriodStart() != null ? s.getCurrentPeriodStart() : start);
            s.setCurrentPeriodEnd(end);
            s.setBillingAnchor(anchor);
            s.setCancelledAt(null);
            s.setUpdatedAt(now);
            if (!auto) {
                // Every paid checkout turns automatic renewal on and saves the card's reusable authorization. Only a card Paystack says cannot
                // be charged again leaves renewal off; the plan is still paid, and the customer is told to add another card.
                boolean saved = saveAuthorization(p, v, now);
                s.setAutoRenew(saved);
                s.setAutoRenewConsentAt(saved ? (p.getConsentAt() != null ? p.getConsentAt() : now) : null);
                if (!saved) p.setFailureNote("This card cannot be used for automatic renewal. Your plan is paid, but add a card that can be charged again so it keeps renewing.");
            }
            subscriptions.save(s);
            p.setStatus("SUCCESS");
            p.setPaymentDetails(v);
            p.setPaidAt(now);
            p.setPeriodStart(start);
            p.setPeriodEnd(end);
            p.setUpdatedAt(now);
            audit.record(p.getBusinessId(), p.getInitiatedBy(), "SUBSCRIPTION_ACTIVATED", "subscription", s.getId(), p.getPlanCode() + " " + p.getBillingCycle() + " " + p.getReference());
            if (!auto) {
                notify[0] = p.getBusinessId();
                msg[0] = "Subscription active";
                msg[1] = "Your " + p.getPlanCode() + " plan is active until " + end.atZone(ZoneOffset.UTC).toLocalDate() + "." + (s.isAutoRenew() ? " It renews automatically each " + (p.getBillingCycle() == BillingCycle.ANNUAL ? "year" : "month") + " until you cancel." : " This card cannot be charged again automatically. Add another card on the Plan page so it keeps renewing.");
            }
        });
        if (notify[0] != null) notifications.publish(notify[0], Permission.SUBSCRIPTION_VIEW, "BILLING", msg[0], msg[1], "/more/plan", "billing-" + paymentId);
        String after = payments.findById(paymentId).map(SubscriptionPayment::getStatus).orElse("PENDING");
        // ShopScale Partners: once the payment is verified and committed, record the referral commission (after commit, own transaction, never throws).
        if ("SUCCESS".equals(after)) affiliateCommissions.afterPaymentSettled(paymentId);
        return after;
    }

    /** Returns a description of the first thing that does not match what we asked Paystack to charge, or null when everything matches. */
    private String mismatch(SubscriptionPayment p, PaystackClient.Verification v) {
        if (!Objects.equals(v.reference(), p.getReference())) return "reference";
        if (v.amountKobo() != PaystackClient.toKobo(p.getAmount())) return "amount";
        if (!"NGN".equalsIgnoreCase(v.currency())) return "currency";
        if (p.getCustomerEmail() != null && !p.getCustomerEmail().isBlank() && !p.getCustomerEmail().trim().equalsIgnoreCase(v.customerEmail() == null ? "" : v.customerEmail().trim())) return "customer";
        var md = v.raw() == null ? null : v.raw().path("metadata");
        if (md != null && md.isObject()) {
            if (md.has("business_id") && !p.getBusinessId().toString().equals(md.path("business_id").asText())) return "business";
            if (md.has("plan") && !p.getPlanCode().equals(md.path("plan").asText())) return "plan";
            if (md.has("cycle") && !p.getBillingCycle().name().equals(md.path("cycle").asText())) return "billing cycle";
            if (md.has("kind") && !p.getKind().equals(md.path("kind").asText())) return "purpose";
        }
        String tx = v.raw() == null ? "" : v.raw().path("id").asText("");
        if (!tx.isBlank() && payments.findByPaystackTransaction(tx).filter((o) -> !o.getId().equals(p.getId())).isPresent()) return "transaction already used";
        return null;
    }

    /** Saves (or replaces) the business's reusable authorization. Returns false when the card cannot be used for recurring charges. */
    private boolean saveAuthorization(SubscriptionPayment p, PaystackClient.Verification v, Instant now) {
        var a = v.raw() == null ? null : v.raw().path("authorization");
        if (a == null || !a.path("reusable").asBoolean(false) || a.path("authorization_code").asText("").isBlank() || !box.isConfigured()) return false;
        if (p.getCustomerEmail() == null || p.getCustomerEmail().isBlank()) return false;
        BillingAuthorization ba = authorizations.findByBusinessIdAndActiveTrue(p.getBusinessId()).orElseGet(BillingAuthorization::new);
        ba.setBusinessId(p.getBusinessId());
        ba.setCustomerEmail(p.getCustomerEmail().trim());
        ba.setAuthorizationCodeEnc(box.encrypt(a.path("authorization_code").asText()));
        ba.setSignature(trim(a.path("signature").asText(null), 120));
        ba.setChannel(trim(a.path("channel").asText(null), 20));
        ba.setCardType(trim(a.path("card_type").asText(null), 30));
        ba.setBank(trim(a.path("bank").asText(null), 80));
        ba.setLast4(trim(a.path("last4").asText(null), 4));
        ba.setExpMonth(trim(a.path("exp_month").asText(null), 2));
        ba.setExpYear(trim(a.path("exp_year").asText(null), 4));
        ba.setReusable(true);
        ba.setActive(true);
        ba.setSourceReference(p.getReference());
        ba.setConsentedAt(p.getConsentAt() == null ? now : p.getConsentAt());
        ba.setUpdatedAt(now);
        authorizations.save(ba);
        return true;
    }

    private static String trim(String s, int max) { return s == null || s.isBlank() ? null : (s.length() > max ? s.substring(0, max) : s); }

    // ---------- history, overview, cancel, auto-renew ----------
    public List<PaymentDto> history() {
        return payments.findTop50ByBusinessIdOrderByCreatedAtDesc(Tenant.businessId()).stream().map(BillingService::dto).toList();
    }

    private static PaymentDto dto(SubscriptionPayment p) {
        return new PaymentDto(p.getId(), p.getPlanCode(), p.getBillingCycle(), p.getKind(), p.getAmount(), p.getStatus(), p.getReference(), p.getPaidAt(), p.getPeriodEnd(), p.getCreatedAt(), p.getFailureNote(), p.getSource());
    }

    /** Automatic-renewal state for the Plan page, read from the database (the single source of truth). No authorization code is ever included. */
    public Overview overview() {
        UUID bid = Tenant.businessId();
        Subscription s = subscriptions.findByBusinessId(bid).orElse(null);
        boolean paid = s != null && !SubscriptionService.FREE.equals(s.getPlanCode()) && s.getStatus() != SubscriptionStatus.TRIALING;
        MethodDto method = authorizations.findByBusinessIdAndActiveTrue(bid).map(a -> new MethodDto(a.getCardType(), a.getLast4(), a.getExpMonth(), a.getExpYear(), a.getBank(), a.getChannel())).orElse(null);
        RenewalDto last = renewals.findTop1ByBusinessIdOrderByCreatedAtDesc(bid).map(r -> new RenewalDto(r.getStatus(), r.getAttemptNo(), r.getDueAt(), r.getNextRetryAt(), r.getFailureReason())).orElse(null);
        BigDecimal amount = null;
        String plan = null;
        if (paid) {
            Plan pl = plans.findById(s.getPlanCode()).orElse(null);
            if (pl != null) { plan = pl.getCode(); amount = s.effectivePrice(pl); }
        }
        PaymentDto lastPaid = payments.findTop50ByBusinessIdOrderByCreatedAtDesc(bid).stream().filter(p -> "SUCCESS".equals(p.getStatus())).findFirst().map(BillingService::dto).orElse(null);
        boolean auto = paid && s.isAutoRenew() && s.getStatus() != SubscriptionStatus.CANCELLED;
        return new Overview(auto, box.isConfigured(), auto ? s.getCurrentPeriodEnd() : null, auto ? amount : null, auto ? plan : null, paid ? s.getBillingCycle() : null,
            method, last, s == null ? null : s.getCancelledAt(), s == null ? null : s.getAutoRenewConsentAt(), lastPaid, graceDays);
    }

    /**
     * Cancelling stops every future automatic charge. The paid period is kept: the plan stays usable until it ends (SubscriptionState).
     * The saved payment method is kept so the customer can resume; it is not used while cancelled.
     */
    public SubscriptionService.Result cancel() {
        UUID bid = Tenant.businessId();
        tx().executeWithoutResult(st -> {
            Subscription s = subscriptions.lockById(subscriptions.findByBusinessId(bid).orElseThrow(() -> ApiException.notFound("No subscription")).getId()).orElseThrow();
            if ((s.getStatus() != SubscriptionStatus.ACTIVE && s.getStatus() != SubscriptionStatus.PAST_DUE) || SubscriptionService.FREE.equals(s.getPlanCode())) throw ApiException.conflict("There is no paid plan to cancel");
            s.setStatus(SubscriptionStatus.CANCELLED);
            s.setAutoRenew(false);
            s.setCancelledAt(Instant.now());
            s.setUpdatedAt(Instant.now());
            audit.record(bid, Tenant.userId(), "SUBSCRIPTION_CANCELLED", "subscription", s.getId(), s.getPlanCode());
        });
        return new SubscriptionService.Result("CANCELLED");
    }

    /**
     * Turn automatic renewal off (the same as cancelling at the end of the period) or back on. Turning on needs a saved reusable payment method and
     * a plan that has not yet ended; a cancelled plan still inside its paid period is resumed. Otherwise the customer pays through checkout,
     * which saves a new card and turns renewal on. Only the customer's own request switches renewal off; nothing here turns it on by itself.
     */
    public SubscriptionService.Result setAutoRenew(boolean enabled) {
        UUID bid = Tenant.businessId();
        tx().executeWithoutResult(st -> {
            Subscription s = subscriptions.lockById(subscriptions.findByBusinessId(bid).orElseThrow(() -> ApiException.notFound("No subscription")).getId()).orElseThrow();
            if (SubscriptionService.FREE.equals(s.getPlanCode()) || s.getStatus() == SubscriptionStatus.TRIALING) throw ApiException.conflict("Automatic renewal applies to a paid plan");
            Instant now = Instant.now();
            if (!enabled) {
                s.setAutoRenew(false);
                s.setUpdatedAt(now);
                audit.record(bid, Tenant.userId(), "AUTO_RENEW_OFF", "subscription", s.getId(), s.getPlanCode());
                return;
            }
            if (s.getStatus() == SubscriptionStatus.SUSPENDED || s.getStatus() == SubscriptionStatus.EXPIRED || !now.isBefore(s.getCurrentPeriodEnd())) throw ApiException.conflict("Your plan has ended. Pay for a plan to start a new subscription.");
            if (authorizations.findByBusinessIdAndActiveTrue(bid).filter(BillingAuthorization::isReusable).isEmpty()) throw ApiException.conflict("There is no saved payment method. Pay for your plan to save a card.");
            s.setAutoRenew(true);
            s.setAutoRenewConsentAt(now);
            if (s.getStatus() == SubscriptionStatus.CANCELLED) { s.setStatus(SubscriptionStatus.ACTIVE); s.setCancelledAt(null); }
            s.setUpdatedAt(now);
            audit.record(bid, Tenant.userId(), "AUTO_RENEW_ON", "subscription", s.getId(), s.getPlanCode());
        });
        return new SubscriptionService.Result("OK");
    }

    // ---------- reminders ----------
    /** Daily: warn owners about plans ending within 3 days. Deduplicated per subscription and day. Plans that renew automatically get a "will renew" notice instead. */
    @Scheduled(cron = "0 0 7 * * *")
    public void remindExpiring() {
        Instant now = Instant.now();
        for (Subscription s : subscriptions.findAll()) {
            if (SubscriptionService.FREE.equals(s.getPlanCode()) || s.getStatus() == SubscriptionStatus.SUSPENDED) continue;
            long days = Duration.between(now, s.getCurrentPeriodEnd()).toDays();
            if (days >= 0 && days <= 3) {
                String date = s.getCurrentPeriodEnd().atZone(ZoneOffset.UTC).toLocalDate().toString();
                boolean auto = s.isAutoRenew() && s.getStatus() == SubscriptionStatus.ACTIVE;
                notifications.publish(s.getBusinessId(), Permission.SUBSCRIPTION_VIEW, "BILLING", auto ? "Your plan renews soon" : "Your plan ends soon",
                    auto ? "Your plan renews automatically on " + date + ". Cancel on the Plan page before then if you do not want to be charged."
                         : "Your plan ends on " + date + ". Renew to keep every feature; otherwise you move to the Free plan and your data stays safe.",
                    "/more/plan", "expiry-" + s.getId() + "-" + date);
            }
        }
    }
}
