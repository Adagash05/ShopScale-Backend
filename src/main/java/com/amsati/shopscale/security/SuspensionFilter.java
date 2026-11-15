package com.amsati.shopscale.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stops every business API call for a business the platform has suspended. Sign-in, sign-out and token refresh stay open so people can see why;
 * their data is untouched. A suspended person's already-issued access token (15 minutes at most) is stopped here too, because the check is on the business.
 */
public class SuspensionFilter extends OncePerRequestFilter {
    private final BusinessStatusCache status;

    public SuspensionFilter(BusinessStatusCache status) { this.status = status; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !p.startsWith("/api/") || p.startsWith("/api/auth/") || p.startsWith("/api/super-admin/") || p.startsWith("/api/public/") || p.startsWith("/api/webhooks/") || p.startsWith("/api/support/");   // a suspended business can still reach support
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthPrincipal p && p.businessId() != null && status.suspended(p.businessId())) {
            res.setStatus(403);
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"code\":\"BUSINESS_SUSPENDED\",\"message\":\"This business account has been suspended. Contact ShopScale support.\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
