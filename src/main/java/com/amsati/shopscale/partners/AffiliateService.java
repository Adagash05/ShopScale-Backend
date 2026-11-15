package com.amsati.shopscale.partners;

import com.amsati.shopscale.auth.User;
import com.amsati.shopscale.auth.UserRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.SecretBox;
import com.amsati.shopscale.security.Tenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Partner applications, the approval workflow, the partner's own profile and bank details. */
@Service
@RequiredArgsConstructor
public class AffiliateService {
    private static final Logger log = LoggerFactory.getLogger(AffiliateService.class);
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no 0/O/1/I
    private static final SecureRandom RNG = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PartnerSettingsService settings;
    private final PartnerAudit audit;
    private final PartnerMail mail;
    private final SecretBox box;
    @Value("${app.partners.referral-base-url:}") private String referralBase;
    @Value("${app.frontend-url:http://localhost:5173}") private String frontendUrl;

    public record Social(@NotBlank @Size(max = 30) String platform, @NotBlank @Size(max = 300) String url) {}

    public record ApplyRequest(
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Size(min = 7, max = 40) String phone,
        @NotEmpty @Size(max = 8) List<@Valid Social> socials,
        @NotNull @Min(0) @Max(2_000_000_000) Integer audienceSize,
        @NotBlank @Size(max = 120) String niche,
        @NotBlank @Size(max = 80) String country,
        @Size(max = 120) String location,
        @NotBlank @Size(min = 20, max = 1000, message = "Tell us a little more (at least 20 characters)") String reason,
        @Size(max = 255) String website,
        @NotNull Boolean acceptTerms) {}

    public record ProfileUpdate(
        @NotBlank @Size(min = 7, max = 40) String phone,
        @NotEmpty @Size(max = 8) List<@Valid Social> socials,
        @NotNull @Min(0) @Max(2_000_000_000) Integer audienceSize,
        @NotBlank @Size(max = 120) String niche,
        @Size(max = 120) String location,
        @Size(max = 255) String website) {}

    public record Dto(UUID id, String status, String code, String referralUrl, String fullName, String email, String phone, String country, String location,
                      List<Social> socials, int audienceSize, String niche, String reason, String website, String statusReason,
                      Instant createdAt, Instant reviewedAt, Instant approvedAt) {}

    public record BankDto(String holderName, String bankName, String bankCode, String accountNumberMasked, Instant updatedAt) {}

    public record BankRequest(@NotBlank @Size(max = 150) String holderName, @NotBlank @Size(max = 100) String bankName,
                              @Size(max = 20) String bankCode, @NotBlank String accountNumber) {}

    // ---------- reading ----------
    private Dto map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String code = rs.getString("code");
        return new Dto(Db.uuid(rs, "id"), rs.getString("status"), code, code == null ? null : referralUrl(code), rs.getString("full_name"), rs.getString("email"), rs.getString("phone"),
            rs.getString("country"), rs.getString("location"), parseSocials(rs.getString("socials")), rs.getInt("audience_size"), rs.getString("niche"), rs.getString("reason"),
            rs.getString("website"), rs.getString("status_reason"), Db.inst(rs, "created_at"), Db.inst(rs, "reviewed_at"), Db.inst(rs, "approved_at"));
    }

    String referralUrl(String code) {
        String base = (referralBase == null || referralBase.isBlank() ? frontendUrl : referralBase).replaceAll("/+$", "");
        return base + "/register?ref=" + code;
    }

    /** The signed-in person's own partner record, or null if they have not applied. */
    public Dto mine() {
        List<Dto> l = jdbc.query("select * from affiliates where user_id = ?", (rs, i) -> map(rs), Tenant.userId());
        return l.isEmpty() ? null : l.get(0);
    }

    /** The signed-in person's approved partner id; anything else is refused. */
    UUID requireApproved() {
        List<UUID> l = jdbc.query("select id from affiliates where user_id = ? and status = 'APPROVED'", (rs, i) -> Db.uuid(rs, "id"), Tenant.userId());
        if (l.isEmpty()) throw ApiException.forbidden("Your partner account is not active. Check your application status.");
        return l.get(0);
    }

    /** The signed-in person's partner id in any state except never applied (read-only views stay available when suspended). */
    UUID requireExisting() {
        List<UUID> l = jdbc.query("select id from affiliates where user_id = ?", (rs, i) -> Db.uuid(rs, "id"), Tenant.userId());
        if (l.isEmpty()) throw ApiException.notFound("You have not applied to ShopScale Partners yet");
        return l.get(0);
    }

    static List<Social> parseSocials(String raw) {
        List<Social> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            int c = line.indexOf(": ");
            if (c > 0) out.add(new Social(line.substring(0, c), line.substring(c + 2)));
        }
        return out;
    }

    static String joinSocials(List<Social> socials) {
        StringBuilder sb = new StringBuilder();
        for (Social s : socials) {
            String url = s.url().trim();
            String platform = s.platform().trim().replaceAll("[\\r\\n:]+", " ");
            String lower = url.toLowerCase(Locale.ROOT);
            if (!(lower.startsWith("https://") || lower.startsWith("http://")) || url.contains("\n") || url.contains("\r") || url.contains(" "))
                throw ApiException.badRequest("Enter " + platform + " as a full link starting with https://");
            sb.append(platform).append(": ").append(url).append('\n');
        }
        return sb.toString().trim();
    }

    static String cleanWebsite(String w) {
        if (w == null || w.isBlank()) return null;
        String t = w.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("https://") || lower.startsWith("http://")) || t.contains(" ")) throw ApiException.badRequest("Enter your website as a full link starting with https://");
        return t;
    }

    // ---------- applying ----------
    @Transactional
    public Dto apply(ApplyRequest r) {
        if (!Boolean.TRUE.equals(r.acceptTerms())) throw ApiException.badRequest("Accept the ShopScale Partners terms to apply");
        Programme prog = settings.get();
        if (!prog.active()) throw ApiException.conflict("ShopScale Partners is not accepting applications right now");
        User u = users.findById(Tenant.userId()).orElseThrow(() -> ApiException.unauthorized("Sign in to continue"));
        String socials = joinSocials(r.socials());
        String website = cleanWebsite(r.website());
        Dto existing = mine();
        UUID id;
        if (existing == null) {
            id = UUID.randomUUID();
            try {
                jdbc.update("insert into affiliates (id, user_id, status, full_name, email, phone, country, location, socials, audience_size, niche, reason, website, terms_accepted_at, terms_version) values (?,?,'PENDING',?,?,?,?,?,?,?,?,?,?,now(),?)",
                    id, u.getId(), r.fullName().trim(), u.getEmail(), r.phone().trim(), r.country().trim(), blank(r.location()), socials, r.audienceSize(), r.niche().trim(), r.reason().trim(), website, prog.termsVersion());
            } catch (DuplicateKeyException e) { throw ApiException.conflict("You have already applied"); }
        } else if ("REJECTED".equals(existing.status())) {
            id = existing.id();
            jdbc.update("update affiliates set status='PENDING', full_name=?, phone=?, country=?, location=?, socials=?, audience_size=?, niche=?, reason=?, website=?, terms_accepted_at=now(), terms_version=?, status_reason=null, reviewed_by=null, reviewed_at=null, updated_at=now() where id=? and status='REJECTED'",
                r.fullName().trim(), r.phone().trim(), r.country().trim(), blank(r.location()), socials, r.audienceSize(), r.niche().trim(), r.reason().trim(), website, prog.termsVersion(), id);
        } else {
            throw ApiException.conflict("You have already applied (status: " + existing.status().toLowerCase(Locale.ROOT) + ")");
        }
        audit.record("APPLICATION_SUBMITTED", "affiliate", id, "from " + u.getEmail());
        mail.applicationSubmitted(u.getEmail(), r.fullName().trim());
        mail.applicationForAdmins(r.fullName().trim() + " (" + u.getEmail() + ")");
        return mine();
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    @Transactional
    public Dto updateProfile(ProfileUpdate r) {
        UUID id = requireExisting();
        jdbc.update("update affiliates set phone=?, socials=?, audience_size=?, niche=?, location=?, website=?, updated_at=now() where id=?",
            r.phone().trim(), joinSocials(r.socials()), r.audienceSize(), r.niche().trim(), blank(r.location()), cleanWebsite(r.website()), id);
        return mine();
    }

    // ---------- administration ----------
    public List<Dto> list(String status, String q, int limit) {
        StringBuilder sql = new StringBuilder("select * from affiliates where 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" and status = ?"); args.add(status.toUpperCase(Locale.ROOT)); }
        if (q != null && !q.isBlank()) { sql.append(" and (lower(full_name) like ? escape '\\' or lower(email) like ? escape '\\' or lower(coalesce(code,'')) like ? escape '\\')"); String l = Db.like(q); args.add(l); args.add(l); args.add(l); }
        sql.append(" order by created_at desc limit ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        return jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
    }

    public Dto get(UUID id) {
        List<Dto> l = jdbc.query("select * from affiliates where id = ?", (rs, i) -> map(rs), id);
        if (l.isEmpty()) throw ApiException.notFound("Partner not found");
        return l.get(0);
    }

    private String uniqueCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(CODE_ALPHABET.charAt(RNG.nextInt(CODE_ALPHABET.length())));
            String c = sb.toString();
            Integer n = jdbc.queryForObject("select count(*) from affiliates where code = ?", Integer.class, c);
            if (n != null && n == 0) return c;
        }
        throw new IllegalStateException("Could not generate a unique referral code");
    }

    @Transactional
    public Dto approve(UUID id) {
        Dto a = get(id);
        if ("APPROVED".equals(a.status())) return a;
        if (!"PENDING".equals(a.status())) throw ApiException.conflict("Only a pending application can be approved. Use reactivate for a suspended partner.");
        String code = a.code() != null ? a.code() : uniqueCode();
        int n = jdbc.update("update affiliates set status='APPROVED', code=?, status_reason=null, reviewed_by=?, reviewed_at=now(), approved_at=now(), updated_at=now() where id=? and status='PENDING'", code, Tenant.userId(), id);
        if (n != 1) throw ApiException.conflict("The application changed. Refresh and try again.");
        audit.record("AFFILIATE_APPROVED", "affiliate", id, "code " + code);
        mail.approved(a.email(), a.fullName(), code);
        return get(id);
    }

    @Transactional
    public Dto reject(UUID id, String reason) {
        Dto a = get(id);
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("Give a reason so the applicant knows why");
        int n = jdbc.update("update affiliates set status='REJECTED', status_reason=?, reviewed_by=?, reviewed_at=now(), updated_at=now() where id=? and status='PENDING'", trim(reason), Tenant.userId(), id);
        if (n != 1) throw ApiException.conflict("Only a pending application can be rejected");
        audit.record("AFFILIATE_REJECTED", "affiliate", id, trim(reason));
        mail.rejected(a.email(), a.fullName(), trim(reason));
        return get(id);
    }

    @Transactional
    public Dto suspend(UUID id, String reason) {
        Dto a = get(id);
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("Give a reason for the suspension");
        int n = jdbc.update("update affiliates set status='SUSPENDED', status_reason=?, reviewed_by=?, reviewed_at=now(), updated_at=now() where id=? and status='APPROVED'", trim(reason), Tenant.userId(), id);
        if (n != 1) throw ApiException.conflict("Only an approved partner can be suspended");
        audit.record("AFFILIATE_SUSPENDED", "affiliate", id, trim(reason));
        mail.suspended(a.email(), a.fullName(), trim(reason));
        return get(id);
    }

    @Transactional
    public Dto reactivate(UUID id) {
        Dto a = get(id);
        int n = jdbc.update("update affiliates set status='APPROVED', status_reason=null, reviewed_by=?, reviewed_at=now(), updated_at=now() where id=? and status='SUSPENDED'", Tenant.userId(), id);
        if (n != 1) throw ApiException.conflict("Only a suspended partner can be reactivated");
        audit.record("AFFILIATE_REACTIVATED", "affiliate", id, null);
        mail.reactivated(a.email(), a.fullName());
        return get(id);
    }

    // ---------- Super Admin: create, edit and (when safe) delete ----------
    public record AdminCreate(@NotBlank @jakarta.validation.constraints.Email @Size(max = 255) String email,
                              @NotBlank @Size(max = 150) String fullName, @NotBlank @Size(min = 7, max = 40) String phone,
                              @NotBlank @Size(max = 80) String country, @NotBlank @Size(max = 120) String niche,
                              @NotNull @Min(0) @Max(2_000_000_000) Integer audienceSize, @Size(max = 8) List<@Valid Social> socials,
                              @Size(max = 120) String location, @Size(max = 255) String website) {}

    public record AdminEdit(@NotBlank @Size(max = 150) String fullName, @NotBlank @Size(min = 7, max = 40) String phone, @NotBlank @Size(max = 80) String country,
                            @NotBlank @Size(max = 120) String niche, @NotNull @Min(0) @Max(2_000_000_000) Integer audienceSize, @Size(max = 8) List<@Valid Social> socials,
                            @Size(max = 120) String location, @Size(max = 255) String website) {}

    /** An administrator registers an existing ShopScale user as an approved partner (they must already have an account to sign in with). */
    @Transactional
    public Dto adminCreate(AdminCreate r) {
        User u = users.findByEmail(r.email().trim().toLowerCase(Locale.ROOT)).filter(User::isActive)
            .orElseThrow(() -> ApiException.badRequest("No active ShopScale account has that email. Ask the person to register first."));
        Integer taken = jdbc.queryForObject("select count(*) from affiliates where user_id = ?", Integer.class, u.getId());
        if (taken != null && taken > 0) throw ApiException.conflict("That account already has a partner record");
        Programme prog = settings.get();
        UUID id = UUID.randomUUID();
        String code = uniqueCode();
        jdbc.update("insert into affiliates (id, user_id, status, code, full_name, email, phone, country, location, socials, audience_size, niche, reason, website, terms_accepted_at, terms_version, reviewed_by, reviewed_at, approved_at) values (?,?,'APPROVED',?,?,?,?,?,?,?,?,?,?,?,now(),?,?,now(),now())",
            id, u.getId(), code, r.fullName().trim(), u.getEmail(), r.phone().trim(), r.country().trim(), blank(r.location()),
            r.socials() == null || r.socials().isEmpty() ? null : joinSocials(r.socials()), r.audienceSize(), r.niche().trim(), "Added by a platform administrator", cleanWebsite(r.website()), prog.termsVersion(), Tenant.userId());
        audit.record("AFFILIATE_CREATED_BY_ADMIN", "affiliate", id, "code " + code);
        mail.approved(u.getEmail(), r.fullName().trim(), code);
        return get(id);
    }

    @Transactional
    public Dto adminEdit(UUID id, AdminEdit r) {
        get(id);
        jdbc.update("update affiliates set full_name=?, phone=?, country=?, niche=?, audience_size=?, socials=?, location=?, website=?, updated_at=now() where id=?",
            r.fullName().trim(), r.phone().trim(), r.country().trim(), r.niche().trim(), r.audienceSize(), r.socials() == null || r.socials().isEmpty() ? null : joinSocials(r.socials()), blank(r.location()), cleanWebsite(r.website()), id);
        audit.record("AFFILIATE_EDITED_BY_ADMIN", "affiliate", id, null);
        return get(id);
    }

    /**
     * Removes a partner record only when nothing depends on it: no referrals, clicks, commissions, withdrawals or bank details.
     * Anything with referral or money history is refused (suspend instead), so attribution and financial records are never destroyed.
     * The foreign keys from those tables (NO ACTION) are a second guard.
     */
    @Transactional
    public void adminDelete(UUID id) {
        get(id);
        Map<String, Object> deps = jdbc.queryForMap("""
            select (select count(*) from affiliate_referrals where affiliate_id = ?) as referrals, (select count(*) from affiliate_clicks where affiliate_id = ?) as clicks,
                   (select count(*) from affiliate_commissions where affiliate_id = ?) as commissions, (select count(*) from affiliate_withdrawals where affiliate_id = ?) as withdrawals,
                   (select count(*) from affiliate_bank_accounts where affiliate_id = ?) as bank""", id, id, id, id, id);
        long blocking = ((Number) deps.get("referrals")).longValue() + ((Number) deps.get("clicks")).longValue() + ((Number) deps.get("commissions")).longValue() + ((Number) deps.get("withdrawals")).longValue();
        if (blocking > 0) throw ApiException.conflict("This partner has referral or payment history (" + deps.get("referrals") + " referrals, " + deps.get("commissions") + " commission records, " + deps.get("withdrawals") + " payouts), which is kept for the records. Suspend the partner instead.");
        jdbc.update("delete from affiliate_bank_accounts where affiliate_id = ?", id);
        jdbc.update("delete from affiliates where id = ?", id);
        audit.record("AFFILIATE_DELETED_BY_ADMIN", "affiliate", id, "no history");
    }

    private static String trim(String s) { String t = s.trim(); return t.length() > 500 ? t.substring(0, 500) : t; }

    // ---------- bank details ----------
    public BankDto bank() {
        UUID id = requireExisting();
        List<BankDto> l = jdbc.query("select holder_name, bank_name, bank_code, account_last4, updated_at from affiliate_bank_accounts where affiliate_id = ?",
            (rs, i) -> new BankDto(rs.getString(1), rs.getString(2), rs.getString(3), mask(rs.getString(4)), Db.inst(rs, "updated_at")), id);
        return l.isEmpty() ? null : l.get(0);
    }

    static String mask(String last4) { return "******" + last4; }

    @Transactional
    public BankDto saveBank(BankRequest r) {
        UUID id = requireApproved();
        String digits = r.accountNumber().replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{10}")) throw ApiException.badRequest("A Nigerian bank account number has 10 digits");
        if (r.holderName().isBlank() || r.bankName().isBlank()) throw ApiException.badRequest("Enter the account name and the bank");
        String enc = box.encrypt(digits);   // refuses (409) when the server has no encryption key; plain text is never stored
        jdbc.update("insert into affiliate_bank_accounts (affiliate_id, holder_name, bank_name, bank_code, account_number_enc, account_last4) values (?,?,?,?,?,?) "
            + "on conflict (affiliate_id) do update set holder_name = excluded.holder_name, bank_name = excluded.bank_name, bank_code = excluded.bank_code, account_number_enc = excluded.account_number_enc, account_last4 = excluded.account_last4, updated_at = now()",
            id, r.holderName().trim(), r.bankName().trim(), blank(r.bankCode()), enc, digits.substring(6));
        audit.record("BANK_DETAILS_CHANGED", "affiliate", id, "account ending " + digits.substring(6));
        return bank();
    }

    // ---------- resources ----------
    public record Resource(UUID id, String kind, String title, String body, String url, int position, boolean published) {}
    public record ResourceRequest(@NotBlank @jakarta.validation.constraints.Pattern(regexp = "GUIDE|FAQ|LINK|ASSET", message = "Kind must be GUIDE, FAQ, LINK or ASSET") String kind, @NotBlank @Size(max = 160) String title, @Size(max = 8000) String body, @Size(max = 500) String url, Integer position, Boolean published) {}

    private static Resource res(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Resource(Db.uuid(rs, "id"), rs.getString("kind"), rs.getString("title"), rs.getString("body"), rs.getString("url"), rs.getInt("position"), rs.getBoolean("published"));
    }

    public List<Resource> resources(boolean includeUnpublished) {
        return jdbc.query("select * from partner_resources " + (includeUnpublished ? "" : "where published ") + "order by position, created_at", (rs, i) -> res(rs));
    }

    private static void checkResource(ResourceRequest r) {
        if (!List.of("GUIDE", "FAQ", "LINK", "ASSET").contains(r.kind())) throw ApiException.badRequest("Kind must be GUIDE, FAQ, LINK or ASSET");
        if (r.url() != null && !r.url().isBlank() && !r.url().toLowerCase(Locale.ROOT).startsWith("https://")) throw ApiException.badRequest("Links must start with https://");
    }

    @Transactional
    public Resource saveResource(UUID id, ResourceRequest r) {
        checkResource(r);
        UUID rid = id == null ? UUID.randomUUID() : id;
        if (id == null) jdbc.update("insert into partner_resources (id, kind, title, body, url, position, published) values (?,?,?,?,?,?,?)", rid, r.kind(), r.title().trim(), blank(r.body()), blank(r.url()), r.position() == null ? 0 : r.position(), r.published() == null || r.published());
        else if (jdbc.update("update partner_resources set kind=?, title=?, body=?, url=?, position=?, published=?, updated_at=now() where id=?", r.kind(), r.title().trim(), blank(r.body()), blank(r.url()), r.position() == null ? 0 : r.position(), r.published() == null || r.published(), id) != 1)
            throw ApiException.notFound("Resource not found");
        audit.record(id == null ? "RESOURCE_CREATED" : "RESOURCE_CHANGED", "resource", rid, r.title());
        return jdbc.queryForObject("select * from partner_resources where id = ?", (rs, i) -> res(rs), rid);
    }

    @Transactional
    public void deleteResource(UUID id) {
        if (jdbc.update("delete from partner_resources where id = ?", id) != 1) throw ApiException.notFound("Resource not found");
        audit.record("RESOURCE_DELETED", "resource", id, null);
    }
}
