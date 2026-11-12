package com.amsati.shopscale.auth;

import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessDto;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.business.Membership;
import com.amsati.shopscale.business.MembershipRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.AuthPrincipal;
import com.amsati.shopscale.security.JwtService;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.security.TokenHash;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final MembershipRepository memberships;
    private final BusinessRepository businesses;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final AccessService access;
    private final AccountService account;
    private final com.amsati.shopscale.subscription.SubscriptionService subscriptions;

    @Value("${app.jwt.refresh-ttl-days:30}")
    private long refreshTtlDays;

    public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72, message = "Use 8 to 72 characters") String password,
        @NotBlank @Size(max = 150) String fullName,
        @Size(max = 40) String phone) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record SwitchRequest(@NotNull UUID businessId) {}
    public record BusinessOption(UUID id, String name, String slug, String roleName) {}

    @Transactional
    public AuthResponse register(RegisterRequest r) {
        String email = r.email().trim().toLowerCase();
        if (users.existsByEmail(email)) throw ApiException.conflict("An account with this email already exists");
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(r.password()));
        u.setFullName(r.fullName().trim());
        u.setPhone(r.phone());
        users.save(u);
        account.sendVerification(u);
        return issue(u, null, UUID.randomUUID());
    }

    @Transactional
    public AuthResponse login(LoginRequest r) {
        User u = users.findByEmail(r.email().trim().toLowerCase())
            .filter(User::isActive)
            .filter(x -> encoder.matches(r.password(), x.getPasswordHash()))
            .orElseThrow(() -> ApiException.unauthorized("Email or password is incorrect"));
        Membership m = memberships.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(u.getId()).orElse(null);
        return issue(u, m, UUID.randomUUID());
    }

    /** Seconds in which a just-rotated token may be presented again (a second tab, or a response lost on a weak network). */
    private static final long RETRY_GRACE_SECONDS = 120;

    /**
     * Rotates the refresh token and keeps the business the session was using.
     * A spent token presented again after the grace window means it may have been copied: the whole sign-in is revoked.
     * Within the window it is treated as a benign retry: the sign-in's other live token is retired and a fresh one issued.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String raw) {
        RefreshToken t = refreshTokens.findByTokenHash(TokenHash.sha256(raw))
            .orElseThrow(() -> ApiException.unauthorized("Your session has ended. Sign in again."));
        Instant now = Instant.now();
        if (t.getRevokedAt() != null) {
            boolean benignRetry = t.getRotatedAt() != null && t.getRotatedAt().isAfter(now.minusSeconds(RETRY_GRACE_SECONDS));
            if (!benignRetry) {
                refreshTokens.revokeFamily(t.getFamilyId(), now);
                throw ApiException.unauthorized("Your session has ended. Sign in again.");
            }
            refreshTokens.rotateActive(t.getFamilyId(), now);
        }
        if (t.getExpiresAt().isBefore(now)) throw ApiException.unauthorized("Your session has ended. Sign in again.");
        User u = users.findById(t.getUserId()).filter(User::isActive)
            .orElseThrow(() -> ApiException.unauthorized("Your session has ended. Sign in again."));
        if (t.getRevokedAt() == null) { t.setRevokedAt(now); t.setRotatedAt(now); }
        Optional<Membership> same = t.getBusinessId() == null ? Optional.empty()
            : memberships.findByUserIdAndBusinessIdAndActiveTrue(u.getId(), t.getBusinessId());
        Membership m = same.or(() -> memberships.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(u.getId())).orElse(null);
        return issue(u, m, t.getFamilyId(), t.getSessionStartedAt());
    }

    @Transactional
    public void logout(String raw) {
        if (raw == null || raw.isBlank()) return;
        refreshTokens.findByTokenHash(TokenHash.sha256(raw)).ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    public record SessionDto(UUID id, String device, Instant signedInAt, Instant lastUsedAt, boolean current) {}

    /** The devices this person is signed in on. currentRaw is the caller's own refresh token (to mark "this device"). */
    @Transactional(readOnly = true)
    public List<SessionDto> sessions(String currentRaw) {
        UUID mine = currentRaw == null ? null : refreshTokens.findByTokenHash(TokenHash.sha256(currentRaw)).map(RefreshToken::getFamilyId).orElse(null);
        return refreshTokens.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(Tenant.userId(), Instant.now()).stream()
            .map(t -> new SessionDto(t.getFamilyId(), deviceLabel(t.getUserAgent()), t.getSessionStartedAt() == null ? t.getCreatedAt() : t.getSessionStartedAt(),
                t.getCreatedAt(), t.getFamilyId().equals(mine)))
            .toList();
    }

    @Transactional
    public void revokeSession(UUID familyId) { refreshTokens.revokeFamilyForUser(familyId, Tenant.userId(), Instant.now()); }

    @Transactional
    public void revokeAllSessions() { refreshTokens.revokeAllForUser(Tenant.userId(), Instant.now()); }

    static String deviceLabel(String ua) {
        if (ua == null || ua.isBlank()) return "Unknown device";
        String u = ua.toLowerCase();
        String browser = u.contains("edg/") ? "Edge" : u.contains("opr/") || u.contains("opera") ? "Opera" : u.contains("chrome") || u.contains("crios") ? "Chrome"
            : u.contains("firefox") || u.contains("fxios") ? "Firefox" : u.contains("safari") ? "Safari" : "Browser";
        String os = u.contains("android") ? "Android" : u.contains("iphone") || u.contains("ipad") ? "iOS" : u.contains("windows") ? "Windows"
            : u.contains("mac os") ? "macOS" : u.contains("linux") ? "Linux" : "";
        return os.isEmpty() ? browser : browser + " on " + os;
    }

    private static String currentUserAgent() {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
            instanceof org.springframework.web.context.request.ServletRequestAttributes a) {
            String ua = a.getRequest().getHeader("User-Agent");
            return ua == null ? null : ua.substring(0, Math.min(ua.length(), 255));
        }
        return null;
    }

    /** Current user, business, role and permissions for the signed-in session. */
    @Transactional(readOnly = true)
    public AuthResponse session() {
        AuthPrincipal p = Tenant.principal();
        User u = users.findById(p.userId()).orElseThrow(() -> ApiException.unauthorized("Sign in to continue"));
        Membership m = p.businessId() == null ? null : memberships.findByUserIdAndBusinessIdAndActiveTrue(p.userId(), p.businessId()).orElse(null);
        return build(u, m, null, null);
    }

    @Transactional(readOnly = true)
    public List<BusinessOption> myBusinesses() {
        List<Membership> ms = memberships.findByUserIdAndActiveTrueOrderByCreatedAtAsc(Tenant.userId());
        Map<UUID, Business> bs = businesses.findAllById(ms.stream().map(Membership::getBusinessId).toList()).stream()
            .collect(Collectors.toMap(Business::getId, b -> b));
        return ms.stream().filter(m -> bs.containsKey(m.getBusinessId()))
            .map(m -> new BusinessOption(m.getBusinessId(), bs.get(m.getBusinessId()).getName(), bs.get(m.getBusinessId()).getSlug(), access.roleInfo(m).name()))
            .toList();
    }

    /** Move this session to another business the user belongs to. Membership is checked independently for each business. */
    @Transactional
    public AuthResponse switchBusiness(UUID businessId) {
        UUID uid = Tenant.userId();
        Membership m = memberships.findByUserIdAndBusinessIdAndActiveTrue(uid, businessId)
            .orElseThrow(() -> ApiException.forbidden("You do not have access to that business"));
        User u = users.findById(uid).orElseThrow(() -> ApiException.unauthorized("Sign in to continue"));
        return issue(u, m, UUID.randomUUID());
    }

    /** Builds tokens for a user in the given membership context (or none, before onboarding). */
    public AuthResponse issue(User u, Membership m, UUID familyId) { return issue(u, m, familyId, null); }

    public AuthResponse issue(User u, Membership m, UUID familyId, Instant sessionStartedAt) {
        AuthPrincipal principal = new AuthPrincipal(u.getId(), u.getEmail(),
            m == null ? null : m.getBusinessId(), m == null ? null : m.getRole());
        String accessToken = jwt.createAccessToken(principal);
        String rawRefresh = TokenHash.newToken();
        RefreshToken rt = new RefreshToken();
        rt.setUserId(u.getId());
        rt.setFamilyId(familyId);
        rt.setBusinessId(m == null ? null : m.getBusinessId());
        rt.setTokenHash(TokenHash.sha256(rawRefresh));
        rt.setExpiresAt(Instant.now().plus(Duration.ofDays(refreshTtlDays)));
        rt.setUserAgent(currentUserAgent());
        rt.setSessionStartedAt(sessionStartedAt == null ? Instant.now() : sessionStartedAt);
        refreshTokens.save(rt);
        if (m != null) m.setLastActiveAt(Instant.now());
        return build(u, m, accessToken, rawRefresh);
    }

    private AuthResponse build(User u, Membership m, String accessToken, String refreshToken) {
        Business b = m == null ? null : businesses.findById(m.getBusinessId()).orElse(null);
        AccessService.RoleInfo info = m == null ? null : access.roleInfo(m);
        List<String> features = List.of();
        String planCode = null, subStatus = null;
        if (b != null) {
            try {
                com.amsati.shopscale.subscription.SubscriptionState st = subscriptions.state(b.getId());
                features = st.features().stream().sorted().toList();
                planCode = st.planCode();
                subStatus = st.status();
            } catch (RuntimeException ignored) { /* no subscription yet: no plan features */ }
        }
        List<String> workflows = b == null ? List.of()
            : com.amsati.shopscale.industry.Workflow.forType(b.getBusinessType()).stream().map(Enum::name).sorted().toList();
        return new AuthResponse(accessToken, refreshToken, accessToken == null ? 0 : jwt.getTtlSeconds(),
            new AuthResponse.UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getPhone()),
            b == null ? null : BusinessDto.of(b), m == null ? null : m.getRole(),
            info == null ? null : info.name(), info == null ? List.of() : List.copyOf(access.names(info.permissions())),
            features, workflows, planCode, subStatus);
    }
}
