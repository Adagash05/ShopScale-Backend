package com.amsati.shopscale.security;

import static org.junit.jupiter.api.Assertions.*;

import io.jsonwebtoken.JwtException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
    private static final String SECRET = "test-secret-test-secret-test-secret-123456";

    @Test
    void roundTripsTenantContext() {
        JwtService jwt = new JwtService(SECRET, 900);
        AuthPrincipal in = new AuthPrincipal(UUID.randomUUID(), "owner@example.com", UUID.randomUUID(), Role.OWNER);
        assertEquals(in, jwt.parse(jwt.createAccessToken(in)));
    }

    @Test
    void roundTripsBeforeOnboarding() {
        JwtService jwt = new JwtService(SECRET, 900);
        AuthPrincipal in = new AuthPrincipal(UUID.randomUUID(), "new@example.com", null, null);
        AuthPrincipal out = jwt.parse(jwt.createAccessToken(in));
        assertNull(out.businessId());
        assertNull(out.role());
    }

    @Test
    void rejectsTamperedAndForeignTokens() {
        JwtService jwt = new JwtService(SECRET, 900);
        String token = jwt.createAccessToken(new AuthPrincipal(UUID.randomUUID(), "a@b.com", UUID.randomUUID(), Role.OWNER));
        assertThrows(JwtException.class, () -> jwt.parse(token.substring(0, token.length() - 2) + "xx"));
        JwtService other = new JwtService("another-secret-another-secret-another-1234", 900);
        assertThrows(JwtException.class, () -> other.parse(token));
    }

    @Test
    void rejectsExpiredTokens() {
        JwtService jwt = new JwtService(SECRET, -10);
        String token = jwt.createAccessToken(new AuthPrincipal(UUID.randomUUID(), "a@b.com", null, null));
        assertThrows(JwtException.class, () -> jwt.parse(token));
    }

    @Test
    void refusesShortSecrets() {
        assertThrows(IllegalStateException.class, () -> new JwtService("short", 900));
    }
}
