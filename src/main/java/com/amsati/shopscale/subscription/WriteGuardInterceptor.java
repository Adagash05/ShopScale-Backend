package com.amsati.shopscale.subscription;

import com.amsati.shopscale.security.Tenant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Blocks writes to business data once a subscription has fully expired. Reads always work, so data is never held hostage. */
@Component
@RequiredArgsConstructor
public class WriteGuardInterceptor implements HandlerInterceptor {
    private final SubscriptionService subscriptions;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String m = req.getMethod();
        if (m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS")) return true;
        subscriptions.requireWritable(Tenant.businessId());
        return true;
    }
}
