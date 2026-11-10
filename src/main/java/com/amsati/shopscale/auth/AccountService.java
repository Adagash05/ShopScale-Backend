package com.amsati.shopscale.auth;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.security.TokenHash;
import com.amsati.shopscale.team.EmailService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Password reset and email verification. Tokens are random, stored only as SHA-256 hashes, single use and expire. */
@Service
@RequiredArgsConstructor
public class AccountService {
    private static final SecureRandom RNG = new SecureRandom();
    private final UserRepository users;
    private final AuthTokenRepository tokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final EmailService email;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    public record ForgotRequest(@NotBlank @Email String email) {}
    public record ResetRequest(@NotBlank String token, @NotBlank @Size(min = 8, max = 72, message = "Use 8 to 72 characters") String password) {}
    public record TokenRequest(@NotBlank String token) {}
    public record VerificationStatus(boolean verified, boolean emailConfigured) {}

    private String base() { return frontendUrl.replaceAll("/+$", ""); }

    private String issue(UUID userId, String kind, Duration ttl) {
        tokens.invalidateOpen(userId, kind, Instant.now());
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        AuthToken t = new AuthToken();
        t.setUserId(userId);
        t.setKind(kind);
        t.setTokenHash(TokenHash.sha256(raw));
        t.setExpiresAt(Instant.now().plus(ttl));
        tokens.save(t);
        return raw;
    }

    private AuthToken consume(String raw, String kind) {
        AuthToken t = tokens.findByTokenHashAndKind(TokenHash.sha256(raw), kind)
            .orElseThrow(() -> ApiException.badRequest("This link is not valid. Ask for a new one."));
        if (t.getUsedAt() != null || t.getExpiresAt().isBefore(Instant.now())) throw ApiException.badRequest("This link has expired. Ask for a new one.");
        t.setUsedAt(Instant.now());
        return t;
    }

    /** Always succeeds from the caller's point of view, so the endpoint cannot be used to discover which emails have accounts. */
    @Transactional
    public void forgotPassword(ForgotRequest r) {
        users.findByEmail(r.email().trim().toLowerCase()).filter(User::isActive).ifPresent(u -> {
            String raw = issue(u.getId(), "RESET", Duration.ofHours(1));
            email.send(u.getEmail(), "Reset your ShopScale password",
                "Hello " + u.getFullName() + ",\n\nUse this link within one hour to choose a new password:\n" + base() + "/reset-password?token=" + raw
                    + "\n\nIf you did not ask for this, ignore this email; your password has not changed.");
        });
    }

    @Transactional
    public void resetPassword(ResetRequest r) {
        AuthToken t = consume(r.token(), "RESET");
        User u = users.findById(t.getUserId()).orElseThrow(() -> ApiException.badRequest("This link is not valid. Ask for a new one."));
        u.setPasswordHash(encoder.encode(r.password()));
        // A reset signs the account out everywhere.
        refreshTokens.revokeAllForUser(u.getId(), Instant.now());
    }

    @Transactional
    public void sendVerification(User u) {
        if (u.isEmailVerified()) return;
        String raw = issue(u.getId(), "VERIFY", Duration.ofDays(3));
        email.send(u.getEmail(), "Confirm your ShopScale email",
            "Hello " + u.getFullName() + ",\n\nConfirm your email address with this link:\n" + base() + "/verify-email?token=" + raw);
    }

    @Transactional
    public void resendVerification() {
        User u = users.findById(Tenant.userId()).orElseThrow(() -> ApiException.unauthorized("Sign in to continue"));
        if (u.isEmailVerified()) return;
        if (!email.isConfigured()) throw ApiException.conflict("Email sending is not set up on this server yet, so a confirmation email cannot be sent.");
        sendVerification(u);
    }

    @Transactional
    public void verify(TokenRequest r) {
        AuthToken t = consume(r.token(), "VERIFY");
        users.findById(t.getUserId()).ifPresent(u -> u.setEmailVerified(true));
    }

    @Transactional(readOnly = true)
    public VerificationStatus status() {
        User u = users.findById(Tenant.userId()).orElseThrow(() -> ApiException.unauthorized("Sign in to continue"));
        return new VerificationStatus(u.isEmailVerified(), email.isConfigured());
    }
}
