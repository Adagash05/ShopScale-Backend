package com.amsati.shopscale.reminder;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import com.amsati.shopscale.subscription.SubscriptionState;
import com.amsati.shopscale.subscription.UsagePeriod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts WhatsApp invoice reminders against the plan's monthly allowance.
 * ShopScale does not send the message; the person opens WhatsApp (or copies the text) from the app. A reminder is counted when the server
 * accepts it and the app then hands it over, so a request the server rejects, or a failed network call, never uses allowance.
 * The same requestId counts once, however many times it is sent (double tap, retry, copy then open).
 */
@Service
@RequiredArgsConstructor
public class ReminderService {
    private final SubscriptionService subscriptions;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record RecordRequest(@NotBlank @Size(max = 64) String requestId, UUID invoiceId, UUID customerId) {}
    public record Usage(Integer limit, long used, Integer remaining, boolean unlimited, boolean included, Instant periodStart, Instant periodEnd) {}
    public record Recorded(boolean counted, Usage usage) {}

    private Usage usageFor(UUID bid, SubscriptionState st) {
        UsagePeriod.Window w = UsagePeriod.billingMonth(st.periodStart(), Instant.now());
        Long used = jdbc.queryForObject("select count(*) from reminder_log where business_id = ? and created_at >= ? and created_at < ?",
            Long.class, bid, java.sql.Timestamp.from(w.start()), java.sql.Timestamp.from(w.end()));
        long u = used == null ? 0 : used;
        Integer limit = st.maxWhatsappReminders();
        boolean included = st.hasFeature("WHATSAPP_REMINDERS") && (limit == null || limit > 0);
        return new Usage(limit, u, ReminderQuota.remaining(limit, u), limit == null, included, w.start(), w.end());
    }

    @Transactional(readOnly = true)
    public Usage usage() {
        UUID bid = Tenant.businessId();
        return usageFor(bid, subscriptions.state(bid));
    }

    @Transactional
    public Recorded record(RecordRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireWritable(bid);
        subscriptions.requireFeature(bid, "WHATSAPP_REMINDERS", "WhatsApp invoice reminders");
        if (r.invoiceId() == null && r.customerId() == null) throw ApiException.badRequest("Say which invoice or customer this reminder is for");
        // Tenant isolation: the invoice or customer must belong to the signed-in business.
        if (r.invoiceId() != null && count("select count(*) from invoices where id = ? and business_id = ?", r.invoiceId(), bid) == 0) throw ApiException.notFound("Invoice not found");
        if (r.customerId() != null && count("select count(*) from customers where id = ? and business_id = ?", r.customerId(), bid) == 0) throw ApiException.notFound("Customer not found");

        subscriptions.lockForCreate(bid, "reminders");   // one reminder decision at a time per business, until this transaction ends
        SubscriptionState st = subscriptions.state(bid);
        if (count("select count(*) from reminder_log where business_id = ? and request_id = ?", bid, r.requestId()) > 0) {
            return new Recorded(false, usageFor(bid, st));   // repeated request: already counted, uses nothing more
        }
        Usage before = usageFor(bid, st);
        switch (ReminderQuota.decide(st.maxWhatsappReminders(), before.used())) {
            case NOT_INCLUDED -> throw ApiException.planLimit("FEATURE_NOT_IN_PLAN",
                "WhatsApp invoice reminders are not included in your " + st.planName() + " plan. Upgrade to use them.",
                Map.of("feature", "WHATSAPP_REMINDERS", "plan", st.planCode()));
            case EXHAUSTED -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("resource", "whatsapp_reminders");
                d.put("limit", st.maxWhatsappReminders());
                d.put("used", before.used());
                d.put("plan", st.planCode());
                d.put("resetsAt", before.periodEnd());
                throw ApiException.planLimit("REMINDER_LIMIT_REACHED", "You have used all " + st.maxWhatsappReminders() + " WhatsApp reminders in your "
                    + st.planName() + " plan for this billing month. They reset on " + before.periodEnd().toString().substring(0, 10) + ", or upgrade for more.", d);
            }
            case ALLOWED -> { /* fall through to record */ }
        }
        jdbc.update("insert into reminder_log (id, business_id, request_id, channel, invoice_id, customer_id, user_id) values (?, ?, ?, 'WHATSAPP', ?, ?, ?)",
            UUID.randomUUID(), bid, r.requestId(), r.invoiceId(), r.customerId(), Tenant.userId());
        audit.record(bid, Tenant.userId(), "REMINDER_RECORDED", r.invoiceId() != null ? "invoice" : "customer", r.invoiceId() != null ? r.invoiceId() : r.customerId(), null);
        return new Recorded(true, usageFor(bid, st));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
