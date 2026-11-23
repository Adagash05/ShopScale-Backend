package com.amsati.shopscale.industry;

import com.amsati.shopscale.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces the V1 product scope on the server, so a hidden feature cannot be reached by calling its API directly.
 * While app.v1-scope is true (the default) the specialised modules answer "not found" for every business, whatever its type or plan.
 * Nothing is deleted: the code, tables and data stay, and setting V1_SCOPE=false restores the modules.
 * Existing custom-domain storefronts keep resolving (GET /api/public/domain is not blocked); only configuring domains is.
 */
@Component
public class V1ScopeInterceptor implements HandlerInterceptor {
    /** Paths that are outside V1. Public so the frontend scope list and the tests can be checked against the same data. */
    public static final List<String> HIDDEN = List.of(
        "/api/restaurant/**", "/api/catering/**", "/api/recipes/**", "/api/production/**", "/api/wastage/**",
        "/api/branches/**", "/api/transfers/**",
        "/api/pricing/**", "/api/sales-documents/**",
        "/api/purchase-orders/**",
        "/api/finance/shifts/**",
        "/api/store/domain/**", "/api/store/enquiries/**", "/api/store/subscribers/**");

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private final boolean enabled;

    public V1ScopeInterceptor(@Value("${app.v1-scope:true}") boolean enabled) { this.enabled = enabled; }

    public static boolean isHidden(String path) {
        for (String p : HIDDEN) if (MATCHER.match(p, path)) return true;
        return false;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (enabled && !"OPTIONS".equalsIgnoreCase(req.getMethod()) && isHidden(req.getRequestURI())) {
            throw ApiException.notFound("This feature is not available.");
        }
        return true;
    }
}
