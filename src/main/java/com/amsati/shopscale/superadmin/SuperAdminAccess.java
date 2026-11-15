package com.amsati.shopscale.superadmin;

import com.amsati.shopscale.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who is a platform Super Admin, decided on the server for every request. Used from @PreAuthorize as {@code @superAdmin.ok()}.
 *
 * Three things must all hold: the bearer token is a Super Admin token signed by this server, its session row is live (not revoked or expired),
 * and the account is currently active with platform_role = SUPER_ADMIN. Business roles (OWNER, MANAGER ...) are a different column on a
 * different table and are never consulted here, so none of them can grant or imply this role. The role is only ever written by the one-time provisioner.
 */
@Component("superAdmin")
public class SuperAdminAccess {
    public record Admin(UUID userId, String email, UUID sessionId, boolean mustChangePassword, String ip) {}

    private static final String ATTR = SuperAdminAccess.class.getName() + ".admin";
    private final JwtService jwt;
    private final JdbcTemplate jdbc;

    public SuperAdminAccess(JwtService jwt, JdbcTemplate jdbc) { this.jwt = jwt; this.jdbc = jdbc; }

    /** A Super Admin who has finished the forced first-login password change. */
    public boolean ok() { Admin a = resolve(); return a != null && !a.mustChangePassword(); }

    /** For the few endpoints a Super Admin may use before changing the initial password (me, change password, logout). */
    public boolean any() { return resolve() != null; }

    /** The signed-in Super Admin; only call from methods already guarded by ok() or any(). */
    public Admin current() {
        Admin a = resolve();
        if (a == null) throw new IllegalStateException("No Super Admin in this request");
        return a;
    }

    private Admin resolve() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (!(ra instanceof ServletRequestAttributes sra)) return null;
        HttpServletRequest req = sra.getRequest();
        Object cached = req.getAttribute(ATTR);
        if (cached instanceof Admin a) return a;
        if (cached == Boolean.FALSE) return null;
        Admin found = lookup(req);
        req.setAttribute(ATTR, found == null ? Boolean.FALSE : found);
        return found;
    }

    private Admin lookup(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        JwtService.AdminClaims c = jwt.parseAdmin(h.substring(7));
        if (c == null) return null;
        var rows = jdbc.query("""
            select u.email, u.must_change_password from admin_sessions s join users u on u.id = s.user_id
            where s.id = ? and s.user_id = ? and s.revoked_at is null and s.expires_at > now()
              and u.active and u.platform_role = 'SUPER_ADMIN'""",
            (rs, i) -> new Object[] {rs.getString(1), rs.getBoolean(2)}, c.sessionId(), c.userId());
        if (rows.isEmpty()) return null;
        return new Admin(c.userId(), (String) rows.get(0)[0], c.sessionId(), (Boolean) rows.get(0)[1], req.getRemoteAddr());
    }
}
