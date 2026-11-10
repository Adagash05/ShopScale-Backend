package com.amsati.shopscale.store;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.branch.BranchRepository;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.payment.PaystackClient;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.SecretBox;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.security.TokenHash;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Hashtable;
import java.util.UUID;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The merchant's side of the online store: details, delivery, their own Paystack connection, and a custom domain. */
@Service
@RequiredArgsConstructor
public class StoreSettingsService {
    private final StoreSettingsRepository settings;
    private final BusinessRepository businesses;
    private final BranchRepository branches;
    private final SubscriptionService subscriptions;
    private final PaystackClient paystack;
    private final SecretBox box;
    private final AccessService access;
    private final AuditService audit;

    @Value("${app.frontend-url:http://localhost:5173}") private String frontendUrl;
    @Value("${app.public-api-url:}") private String publicApiUrl;
    @Value("${app.storefront-url:}") private String storefrontUrl;

    public record SettingsRequest(boolean enabled, @Size(max = 120) String title, @Size(max = 500) String description, @Size(max = 30) String phone,
                                  @Size(max = 30) String whatsapp, boolean pickupEnabled, boolean deliveryEnabled,
                                  @NotNull @DecimalMin("0") BigDecimal deliveryFee, @Size(max = 300) String deliveryNote, boolean payLaterEnabled) {}
    public record PaystackRequest(@NotBlank @Size(max = 100) String publicKey, @NotBlank @Size(max = 120) String secretKey) {}
    public record DomainRequest(@NotBlank @Size(max = 150) String domain) {}
    public record SettingsDto(boolean available, boolean enabled, String slug, String storeUrl, String title, String description, String phone, String whatsapp,
                              boolean pickupEnabled, boolean deliveryEnabled, BigDecimal deliveryFee, String deliveryNote, boolean payLaterEnabled,
                              boolean paystackConnected, String paystackPublicKey, boolean paystackTestMode, boolean encryptionConfigured, String webhookUrl,
                              boolean customDomainAvailable, String customDomain, boolean domainVerified, String domainToken, String domainTarget) {}

    private String base() { return (storefrontUrl == null || storefrontUrl.isBlank() ? frontendUrl.replaceAll("/+$", "") + "/s" : storefrontUrl.replaceAll("/+$", "")); }
    private String api() { return publicApiUrl == null || publicApiUrl.isBlank() ? "https://YOUR-API-HOST" : publicApiUrl.replaceAll("/+$", ""); }

    StoreSettings load(UUID bid) {
        return settings.findById(bid).orElseGet(() -> {
            StoreSettings s = new StoreSettings();
            s.setBusinessId(bid);
            return settings.save(s);
        });
    }

    @Transactional
    public SettingsDto get() {
        UUID bid = Tenant.businessId();
        return dto(bid, load(bid));
    }

    private SettingsDto dto(UUID bid, StoreSettings s) {
        Business b = businesses.findById(bid).orElseThrow();
        var st = subscriptions.state(bid);
        boolean connected = s.getPaystackSecretEnc() != null;
        String host = s.getCustomDomain();
        return new SettingsDto(st.hasFeature("STOREFRONT"), s.isEnabled(), b.getSlug(), base() + "/" + b.getSlug(), s.getTitle() == null ? b.getName() : s.getTitle(), s.getDescription(),
            s.getPhone(), s.getWhatsapp(), s.isPickupEnabled(), s.isDeliveryEnabled(), s.getDeliveryFee(), s.getDeliveryNote(), s.isPayLaterEnabled(),
            connected, connected ? s.getPaystackPublicKey() : null, connected && s.getPaystackPublicKey() != null && s.getPaystackPublicKey().startsWith("pk_test"),
            box.isConfigured(), api() + "/api/webhooks/paystack/store/" + b.getSlug(), st.hasFeature("CUSTOM_DOMAINS"), host, s.isDomainVerified(), s.getDomainToken(),
            frontendUrl.replaceAll("^https?://", "").replaceAll("/.*$", ""));
    }

    @Transactional
    public SettingsDto update(SettingsRequest r) {
        UUID bid = Tenant.businessId();
        StoreSettings s = load(bid);
        if (r.enabled()) {
            access.require(Permission.STORE_PUBLISH);
            subscriptions.requireFeature(bid, "STOREFRONT", "The online store");
            if (!r.pickupEnabled() && !r.deliveryEnabled()) throw ApiException.badRequest("Allow pickup, delivery or both");
            if (!r.payLaterEnabled() && s.getPaystackSecretEnc() == null) throw ApiException.badRequest("Connect your Paystack account or allow pay on pickup/delivery, so customers have a way to pay");
        }
        if (r.deliveryEnabled() || r.pickupEnabled() || !r.enabled()) { /* ok */ }
        if ((r.deliveryEnabled() || r.pickupEnabled() != s.isPickupEnabled() || r.deliveryFee().compareTo(s.getDeliveryFee()) != 0) ) access.require(Permission.STORE_DELIVERY_MANAGE);
        s.setEnabled(r.enabled());
        s.setTitle(blank(r.title()));
        s.setDescription(blank(r.description()));
        s.setPhone(blank(r.phone()));
        s.setWhatsapp(blank(r.whatsapp()));
        s.setPickupEnabled(r.pickupEnabled());
        s.setDeliveryEnabled(r.deliveryEnabled());
        s.setDeliveryFee(Money.scale(r.deliveryFee()));
        s.setDeliveryNote(blank(r.deliveryNote()));
        s.setPayLaterEnabled(r.payLaterEnabled());
        s.setUpdatedAt(Instant.now());
        audit.record(bid, Tenant.userId(), r.enabled() ? "STORE_PUBLISHED" : "STORE_SETTINGS_SAVED", "store", bid, null);
        return dto(bid, s);
    }

    /** Saves the merchant's own Paystack keys after proving the secret key works. Money from the store goes to THEIR account. */
    @Transactional
    public SettingsDto connectPaystack(PaystackRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireFeature(bid, "STOREFRONT", "The online store");
        String pk = r.publicKey().trim();
        String sk = r.secretKey().trim();
        if (!pk.startsWith("pk_")) throw ApiException.badRequest("The public key should start with pk_");
        if (!sk.startsWith("sk_")) throw ApiException.badRequest("The secret key should start with sk_");
        if (pk.startsWith("pk_test") != sk.startsWith("sk_test")) throw ApiException.badRequest("Use both test keys or both live keys, not one of each");
        paystack.checkKey(sk);
        StoreSettings s = load(bid);
        s.setPaystackPublicKey(pk);
        s.setPaystackSecretEnc(box.encrypt(sk));
        s.setPaystackConnectedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "STORE_PAYSTACK_CONNECTED", "store", bid, pk.startsWith("pk_test") ? "test keys" : "live keys");
        return dto(bid, s);
    }

    @Transactional
    public SettingsDto disconnectPaystack() {
        UUID bid = Tenant.businessId();
        StoreSettings s = load(bid);
        s.setPaystackPublicKey(null);
        s.setPaystackSecretEnc(null);
        s.setPaystackConnectedAt(null);
        if (!s.isPayLaterEnabled()) s.setEnabled(false);   // a store with no way to pay is switched off
        audit.record(bid, Tenant.userId(), "STORE_PAYSTACK_DISCONNECTED", "store", bid, null);
        return dto(bid, s);
    }

    // ---------- custom domain ----------
    @Transactional
    public SettingsDto setDomain(DomainRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireFeature(bid, "CUSTOM_DOMAINS", "A custom domain");
        String d = r.domain().trim().toLowerCase().replaceAll("^https?://", "").replaceAll("/.*$", "");
        if (!d.matches("^(?=.{4,150}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$")) throw ApiException.badRequest("Enter a domain like shop.yourbusiness.com");
        if (settings.findByCustomDomainIgnoreCase(d).filter(x -> !x.getBusinessId().equals(bid)).isPresent()) throw ApiException.conflict("That domain is already used by another store");
        StoreSettings s = load(bid);
        s.setCustomDomain(d);
        s.setDomainToken(TokenHash.newToken());
        s.setDomainVerified(false);
        s.setUpdatedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "STORE_DOMAIN_SET", "store", bid, d);
        return dto(bid, s);
    }

    @Transactional
    public SettingsDto removeDomain() {
        UUID bid = Tenant.businessId();
        StoreSettings s = load(bid);
        s.setCustomDomain(null);
        s.setDomainToken(null);
        s.setDomainVerified(false);
        return dto(bid, s);
    }

    /** Looks for the TXT record _shopscale-verify.<domain> = token. Nothing is marked verified without it. */
    @Transactional
    public SettingsDto verifyDomain() {
        UUID bid = Tenant.businessId();
        StoreSettings s = load(bid);
        if (s.getCustomDomain() == null) throw ApiException.badRequest("Add a domain first");
        boolean found = false;
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "3000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            Attributes attrs = new InitialDirContext(env).getAttributes("_shopscale-verify." + s.getCustomDomain(), new String[] {"TXT"});
            Attribute txt = attrs.get("TXT");
            for (int i = 0; txt != null && i < txt.size(); i++) if (String.valueOf(txt.get(i)).replace("\"", "").trim().equals(s.getDomainToken())) found = true;
        } catch (Exception e) { found = false; }
        if (!found) throw ApiException.conflict("We could not find the verification record yet. DNS changes can take a while; try again soon.");
        s.setDomainVerified(true);
        audit.record(bid, Tenant.userId(), "STORE_DOMAIN_VERIFIED", "store", bid, s.getCustomDomain());
        return dto(bid, s);
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
