package com.amsati.shopscale.partners;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Referral tracking decided on the server.
 *
 * - A visit to a partner link records a click (one per visitor, partner and day).
 * - When a person registers, the latest click from that browser inside the attribution window decides the partner (last referral wins).
 *   A bare code with no recorded click is accepted too, marked as such, so a blocked tracking call does not lose a genuine referral.
 * - A customer is attributed once. The row is never edited by any request; there is no endpoint that changes it.
 * - Partners cannot refer themselves (same account, same email once "+tags" and Gmail dots are removed, or same phone number).
 * - Attribution runs after registration commits, in its own transaction: a tracking problem can never block or undo a sign-up.
 */
@Service
@RequiredArgsConstructor
public class AttributionService {
    private static final Logger log = LoggerFactory.getLogger(AttributionService.class);

    private final JdbcTemplate jdbc;
    private final PartnerSettingsService settings;
    private final PartnerAudit audit;
    private final PlatformTransactionManager txm;
    @Value("${app.jwt.secret}") private String hashKey;

    static boolean validVisitor(String v) { return v != null && v.matches("[A-Za-z0-9_-]{8,64}"); }
    static String normCode(String c) { return c == null ? "" : c.trim().toUpperCase(Locale.ROOT); }

    /** Records a click. Always quiet: the caller learns nothing about which codes exist. */
    public void recordClick(String code, String visitorId, String source) {
        try {
            String c = normCode(code);
            if (!c.matches("[A-Z0-9]{4,24}") || !validVisitor(visitorId)) return;
            Programme p = settings.get();
            if (!p.active()) return;
            List<UUID> a = jdbc.query("select id from affiliates where code = ? and status = 'APPROVED'", (rs, i) -> Db.uuid(rs, "id"), c);
            if (a.isEmpty()) return;
            HttpServletRequest req = request();
            String ref = req == null ? null : host(req.getHeader("Referer"));
            jdbc.update("insert into affiliate_clicks (id, affiliate_id, visitor_id, click_day, source, referrer_host, ip_hash, user_agent) values (?,?,?,?,?,?,?,?) on conflict (affiliate_id, visitor_id, click_day) do nothing",
                UUID.randomUUID(), a.get(0), visitorId, java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC)), cut(source, 120), cut(ref, 120), req == null ? null : ipHash(req), req == null ? null : cut(req.getHeader("User-Agent"), 200));
        } catch (Exception e) {
            log.warn("Could not record a referral click: {}", e.toString());
        }
    }

    /**
     * Called while a registration is being saved. Waits for the commit, then attributes in a separate transaction.
     * Never throws.
     */
    public void attributeAfterCommit(UUID userId, String email, String phone, String code, String visitorId) {
        if ((code == null || code.isBlank()) && !validVisitor(visitorId)) return;
        Runnable work = () -> {
            try {
                new TransactionTemplate(txm, newTx()).executeWithoutResult(st -> attribute(userId, email, phone, code, visitorId));
            } catch (Exception e) {
                log.error("Referral attribution failed for a new account (the account itself was created): {}", e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { work.run(); }
            });
        } else work.run();
    }

    private static TransactionDefinition newTx() {
        org.springframework.transaction.support.DefaultTransactionDefinition d = new org.springframework.transaction.support.DefaultTransactionDefinition();
        d.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return d;
    }

    record Pick(UUID affiliateId, UUID clickId, Instant clickAt, String source) {}

    void attribute(UUID userId, String email, String phone, String code, String visitorId) {
        Programme p = settings.get();
        if (!p.active()) return;
        Pick pick = null;
        if (validVisitor(visitorId)) {
            List<Pick> l = jdbc.query("select c.id, c.affiliate_id, c.clicked_at from affiliate_clicks c join affiliates a on a.id = c.affiliate_id "
                + "where c.visitor_id = ? and a.status = 'APPROVED' and c.clicked_at > now() - make_interval(days => ?) order by c.clicked_at desc limit 1",
                (rs, i) -> new Pick(Db.uuid(rs, "affiliate_id"), Db.uuid(rs, "id"), Db.inst(rs, "clicked_at"), "CLICK"), visitorId, p.attributionDays());
            if (!l.isEmpty()) pick = l.get(0);
        }
        if (pick == null && code != null && !code.isBlank()) {
            List<Pick> l = jdbc.query("select id from affiliates where code = ? and status = 'APPROVED'", (rs, i) -> new Pick(Db.uuid(rs, "id"), null, null, "CODE"), normCode(code));
            if (!l.isEmpty()) pick = l.get(0);
        }
        if (pick == null) return;

        List<String[]> aff = jdbc.query("select user_id::text, email, phone from affiliates where id = ?", (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, pick.affiliateId());
        if (aff.isEmpty()) return;
        if (selfReferral(userId, email, phone, aff.get(0))) {
            audit.recordAs(null, "SELF_REFERRAL_BLOCKED", "affiliate", pick.affiliateId(), "a partner's own account tried to register through their link");
            return;
        }
        jdbc.update("insert into affiliate_referrals (id, affiliate_id, referred_user_id, click_id, attribution_source, click_at, rate, commission_months, holding_days) values (?,?,?,?,?,?,?,?,?) on conflict (referred_user_id) do nothing",
            UUID.randomUUID(), pick.affiliateId(), userId, pick.clickId(), pick.source(), Db.ts(pick.clickAt()), p.commissionRate(), p.commissionMonths(), p.holdingDays());
    }

    static boolean selfReferral(UUID userId, String email, String phone, String[] affiliate) {
        if (affiliate[0].equals(userId.toString())) return true;
        if (email != null && affiliate[1] != null && normEmail(email).equals(normEmail(affiliate[1]))) return true;
        String a = digits(phone), b = digits(affiliate[2]);
        return a.length() >= 7 && b.length() >= 7 && a.substring(a.length() - 7).equals(b.substring(b.length() - 7));
    }

    /** Lower-case; drop "+tag"; drop dots for Gmail-style addresses, so one inbox cannot pose as two people. */
    static String normEmail(String e) {
        String s = e.trim().toLowerCase(Locale.ROOT);
        int at = s.lastIndexOf('@');
        if (at < 1) return s;
        String local = s.substring(0, at), domain = s.substring(at + 1);
        int plus = local.indexOf('+');
        if (plus > 0) local = local.substring(0, plus);
        if (domain.equals("gmail.com") || domain.equals("googlemail.com")) { local = local.replace(".", ""); domain = "gmail.com"; }
        return local + "@" + domain;
    }

    static String digits(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    private static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }

    private String ipHash(HttpServletRequest req) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(hashKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(m.doFinal(req.getRemoteAddr().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { return null; }
    }

    private static String host(String url) {
        try { return url == null ? null : java.net.URI.create(url).getHost(); } catch (Exception e) { return null; }
    }

    private static String cut(String s, int max) { return s == null ? null : (s.length() > max ? s.substring(0, max) : s); }
}
