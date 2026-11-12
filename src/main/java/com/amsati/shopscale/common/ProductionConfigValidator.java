package com.amsati.shopscale.common;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start in production (APP_ENV=production, the default) with settings that would make payments silently misbehave:
 * localhost callback or CORS addresses, a missing Paystack platform key, or a missing/invalid encryption key (without it, saved
 * payment authorizations and merchant Paystack keys cannot be stored, and a changed key makes stored ones unreadable).
 * Set APP_ENV=development for local work. Messages name the variable and never print its value.
 */
@Component
public class ProductionConfigValidator implements InitializingBean {
    @Value("${app.env:production}") private String env;
    @Value("${app.frontend-url:}") private String frontendUrl;
    @Value("${app.public-api-url:}") private String publicApiUrl;
    @Value("${app.cors.allowed-origins:}") private String cors;
    @Value("${app.paystack.secret-key:}") private String paystackKey;
    @Value("${app.encryption-key:}") private String encryptionKey;
    @Value("${app.jwt.secret:}") private String jwtSecret;
    @Value("${app.cookie.secure:true}") private boolean cookieSecure;

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems(env, frontendUrl, publicApiUrl, cors, paystackKey, encryptionKey, jwtSecret, cookieSecure);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("ShopScale cannot start in production with this configuration:\n - " + String.join("\n - ", problems)
                + "\nFix the variables above, or set APP_ENV=development for local work.");
        }
    }

    public static List<String> problems(String env, String frontendUrl, String publicApiUrl, String cors, String paystackKey, String encryptionKey, String jwtSecret, boolean cookieSecure) {
        List<String> out = new ArrayList<>();
        if (!"production".equalsIgnoreCase(env == null ? "production" : env.trim())) return out;
        if (!isPublicHttps(frontendUrl)) out.add("FRONTEND_URL must be the public https address of the app (for example https://app.shopscale.com.ng); it is used for payment return and email links");
        if (!isPublicHttps(publicApiUrl)) out.add("PUBLIC_API_URL must be the public https address of this API (for example https://api.shopscale.com.ng)");
        if (cors == null || cors.isBlank()) out.add("CORS_ALLOWED_ORIGINS is empty");
        else for (String o : cors.split(",")) if (!isPublicHttps(o.trim())) out.add("CORS_ALLOWED_ORIGINS contains an address that is not a public https origin");
        if (paystackKey == null || paystackKey.isBlank()) out.add("PAYSTACK_SECRET_KEY (the AMSATI platform key) is not set");
        else if (!paystackKey.trim().startsWith("sk_")) out.add("PAYSTACK_SECRET_KEY does not look like a Paystack secret key (it should start with sk_)");
        try {
            if (encryptionKey == null || encryptionKey.isBlank() || Base64.getDecoder().decode(encryptionKey.trim()).length != 32) out.add("APP_ENCRYPTION_KEY must be 32 random bytes encoded as base64 (openssl rand -base64 32); keep the same value across restarts and deployments");
        } catch (IllegalArgumentException e) { out.add("APP_ENCRYPTION_KEY is not valid base64"); }
        if (jwtSecret == null || jwtSecret.length() < 32) out.add("JWT_SECRET must be at least 32 characters");
        if (!cookieSecure) out.add("COOKIE_SECURE must be true in production");
        return out;
    }

    static boolean isPublicHttps(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI u = URI.create(url.trim());
            String h = u.getHost();
            if (!"https".equalsIgnoreCase(u.getScheme()) || h == null) return false;
            h = h.toLowerCase();
            return !(h.equals("localhost") || h.endsWith(".localhost") || h.equals("127.0.0.1") || h.equals("0.0.0.0") || h.equals("::1") || h.endsWith(".local"));
        } catch (IllegalArgumentException e) { return false; }
    }
}
