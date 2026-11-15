package com.amsati.shopscale.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-ttl-seconds:900}") long ttlSeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlSeconds = ttlSeconds;
    }

    public long getTtlSeconds() { return ttlSeconds; }

    public String createAccessToken(AuthPrincipal p) {
        Instant now = Instant.now();
        var b = Jwts.builder()
            .subject(p.userId().toString())
            .claim("email", p.email())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlSeconds)));
        if (p.businessId() != null) b.claim("bid", p.businessId().toString());
        if (p.role() != null) b.claim("role", p.role().name());
        return b.signWith(key).compact();
    }

    public AuthPrincipal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        String bid = c.get("bid", String.class);
        String role = c.get("role", String.class);
        return new AuthPrincipal(
            UUID.fromString(c.getSubject()),
            c.get("email", String.class),
            bid == null ? null : UUID.fromString(bid),
            role == null ? null : Role.valueOf(role));
    }

    /** What a Super Admin token asserts. It is only a claim: the backend still checks the session row and the account on every request. */
    public record AdminClaims(UUID userId, String email, UUID sessionId) {}

    /** A short-lived platform token. It has no business id, so no tenant endpoint accepts it as a merchant. */
    public String createAdminToken(UUID userId, String email, UUID sessionId, Instant expires) {
        return Jwts.builder().subject(userId.toString()).claim("email", email).claim("plat", "SUPER_ADMIN").claim("sid", sessionId.toString())
            .issuedAt(Date.from(Instant.now())).expiration(Date.from(expires)).signWith(key).compact();
    }

    /** Null when the token is not a valid, unexpired Super Admin token. */
    public AdminClaims parseAdmin(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!"SUPER_ADMIN".equals(c.get("plat", String.class)) || c.get("sid", String.class) == null) return null;
            return new AdminClaims(UUID.fromString(c.getSubject()), c.get("email", String.class), UUID.fromString(c.get("sid", String.class)));
        } catch (Exception e) { return null; }
    }
}
