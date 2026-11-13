package com.amsati.shopscale.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class RateLimitKeyTest {
    private HttpServletRequest req(String path) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRequestURI()).thenReturn(path);
        when(r.getRemoteAddr()).thenReturn("203.0.113.9");
        return r;
    }

    @Test void emailSendingEndpointsHaveTheirOwnBuckets() {
        assertNotEquals(RateLimitFilter.key(req("/api/auth/register")), RateLimitFilter.key(req("/api/auth/login")));
        assertNotEquals(RateLimitFilter.key(req("/api/auth/resend-verification")), RateLimitFilter.key(req("/api/auth/login")));
        assertNotEquals(RateLimitFilter.key(req("/api/auth/forgot-password")), RateLimitFilter.key(req("/api/auth/register")));
    }
}
