package com.amsati.shopscale.industry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsati.shopscale.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class V1ScopeInterceptorTest {
    private static MockHttpServletRequest req(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRequestURI(uri);
        return r;
    }

    @Test
    void specialisedModulesAreBlockedWhileTheScopeIsOn() {
        V1ScopeInterceptor on = new V1ScopeInterceptor(true);
        for (String p : List.of("/api/restaurant/orders", "/api/catering/events", "/api/recipes", "/api/production/runs", "/api/wastage",
            "/api/branches", "/api/transfers", "/api/pricing/tiers", "/api/sales-documents",
            "/api/purchase-orders/5/receive", "/api/finance/shifts/current", "/api/store/domain", "/api/store/domain/verify",
            "/api/store/enquiries", "/api/store/subscribers")) {
            assertThrows(ApiException.class, () -> on.preHandle(req("GET", p), new MockHttpServletResponse(), new Object()), p);
        }
    }

    @Test
    void approvedV1FeaturesAreNeverBlocked() {
        V1ScopeInterceptor on = new V1ScopeInterceptor(true);
        for (String p : List.of("/api/sales", "/api/products", "/api/inventory/adjust", "/api/customers", "/api/expenses", "/api/suppliers",
            "/api/purchases", "/api/finance/summary", "/api/finance/debtors", "/api/finance/payables", "/api/finance/accounts",
            "/api/store/settings", "/api/store/orders", "/api/store/paystack", "/api/public/store/aminu/products", "/api/public/domain",
            "/api/webhooks/paystack", "/api/subscriptions/current", "/api/team", "/api/dashboard", "/api/auth/login")) {
            assertDoesNotThrow(() -> on.preHandle(req("GET", p), new MockHttpServletResponse(), new Object()), p);
            assertFalse(V1ScopeInterceptor.isHidden(p), p);
        }
    }

    @Test
    void turningTheScopeOffRestoresTheModules() {
        V1ScopeInterceptor off = new V1ScopeInterceptor(false);
        assertTrue(V1ScopeInterceptor.isHidden("/api/restaurant/orders"));
        assertDoesNotThrow(() -> off.preHandle(req("GET", "/api/restaurant/orders"), new MockHttpServletResponse(), new Object()));
    }
}
