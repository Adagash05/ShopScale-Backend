package com.amsati.shopscale.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token travels only in an HttpOnly cookie scoped to /api/auth, so page scripts (and any XSS) can never
 * read it. app.shopscale.com.ng and api.shopscale.com.ng are the same site, so SameSite=Strict still sends it.
 */
@Component
public class RefreshCookies {
    public static final String NAME = "ss_rt";

    @Value("${app.cookie.secure:true}") private boolean secure;
    @Value("${app.cookie.same-site:Strict}") private String sameSite;
    @Value("${app.cookie.domain:}") private String domain;
    @Value("${app.jwt.refresh-ttl-days:30}") private long days;

    public ResponseCookie issue(String raw) { return build(raw, Duration.ofDays(days)); }

    public ResponseCookie clear() { return build("", Duration.ZERO); }

    private ResponseCookie build(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(NAME, value)
            .httpOnly(true).secure(secure).sameSite(sameSite).path("/api/auth").maxAge(maxAge);
        if (domain != null && !domain.isBlank()) b.domain(domain.trim());
        return b.build();
    }

    public String read(HttpServletRequest req) {
        Cookie[] cs = req.getCookies();
        if (cs == null) return null;
        for (Cookie c : cs) if (NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) return c.getValue();
        return null;
    }
}
