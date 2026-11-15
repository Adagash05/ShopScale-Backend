package com.amsati.shopscale.superadmin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Records every change a Super Admin makes through the existing partner administration API (approve, suspend, adjust or reverse a commission,
 * approve or pay a withdrawal, settings, resources). Reads are not recorded. The record carries the method, path and outcome, never the request body
 * (which can hold bank details). The reason a partner action was taken is also kept in the partner module's own audit log.
 */
@Component
public class AdminActionAuditInterceptor implements HandlerInterceptor {
    private final SuperAdminAccess access;
    private final AdminAudit audit;

    public AdminActionAuditInterceptor(SuperAdminAccess access, AdminAudit audit) { this.access = access; this.audit = audit; }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        String m = req.getMethod();
        if (m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS") || !access.any()) return;
        String path = req.getRequestURI().replaceFirst("^/api/admin/partners", "");
        boolean ok = ex == null && res.getStatus() < 400;
        String action = "PARTNER_ADMIN_" + (path.contains("/withdrawals") ? "PAYOUT" : path.contains("/commissions") || path.contains("/refunds") ? "COMMISSION" : path.contains("/affiliates") ? "STATUS" : "CONFIG");
        audit.record(action, "partner-api", path.length() > 80 ? path.substring(0, 80) : path, null, ok, null, Map.of("method", m, "httpStatus", res.getStatus()));
    }

    @Configuration
    static class Registration implements WebMvcConfigurer {
        private final AdminActionAuditInterceptor interceptor;
        Registration(AdminActionAuditInterceptor interceptor) { this.interceptor = interceptor; }
        @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(interceptor).addPathPatterns("/api/admin/partners/**"); }
    }
}
