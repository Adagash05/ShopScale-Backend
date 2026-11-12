package com.amsati.shopscale.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;

/** Small in-memory limiter for credential endpoints (per client address). Use a shared store if you run several instances. */
public class RateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_REQUESTS = 30;
    private static final long WINDOW_MS = 60_000;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !(p.equals("/api/auth/login") || p.equals("/api/auth/register") || p.equals("/api/auth/refresh") || p.equals("/api/auth/forgot-password") || p.equals("/api/auth/reset-password") || p.equals("/api/auth/verify-email")
            || (p.startsWith("/api/public/store/") && req.getMethod().equals("POST")) || (p.startsWith("/api/public/orders/") && req.getMethod().equals("POST")) || p.equals("/api/team/invitations/accept") || p.equals("/api/team/invitations/preview"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(req.getRemoteAddr(), k -> new ArrayDeque<>());
        boolean limited;
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            limited = q.size() >= MAX_REQUESTS;
            if (!limited) q.addLast(now);
        }
        if (limited) {
            res.setStatus(429);
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Too many attempts. Wait a minute and try again.\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
