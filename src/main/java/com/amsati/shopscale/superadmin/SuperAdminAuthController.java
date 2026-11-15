package com.amsati.shopscale.superadmin;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Super Admin sign-in. Separate from /api/auth: it only accepts accounts whose platform_role is SUPER_ADMIN, issues a short platform token
 * (no refresh cookie, no business), and keeps its own session rows so logout and password changes end sessions immediately.
 */
@RestController
@RequestMapping("/api/super-admin/auth")
public class SuperAdminAuthController {
    static final int MAX_FAILURES = 5;
    static final long LOCK_MINUTES = 15;
    // A real BCrypt hash of a random string, so an unknown email costs the same time as a wrong password.
    private final String dummyHash;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AdminAudit audit;
    private final SuperAdminAccess access;
    private final long sessionMinutes;

    public SuperAdminAuthController(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt, AdminAudit audit, SuperAdminAccess access,
                                    @Value("${app.super-admin.session-minutes:30}") long sessionMinutes) {
        this.jdbc = jdbc; this.encoder = encoder; this.jwt = jwt; this.audit = audit; this.access = access;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
        this.sessionMinutes = Math.max(5, Math.min(sessionMinutes, 480));
    }

    public record LoginRequest(@NotBlank @Size(max = 255) String email, @NotBlank @Size(max = 200) String password) {}
    public record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword, @NotBlank @Size(max = 200) String newPassword) {}

    @PostMapping("/login")
    @Transactional(noRollbackFor = ApiException.class)   // failure counters and the failed-login audit record must be kept
    public Map<String, Object> login(@Valid @RequestBody LoginRequest r, HttpServletRequest req) {
        String email = r.email().trim().toLowerCase();
        String ip = req.getRemoteAddr();
        var rows = jdbc.queryForList("select id, email, password_hash, full_name, active, must_change_password, locked_until, failed_logins from users where lower(email) = ? and platform_role = 'SUPER_ADMIN'", email);
        Map<String, Object> u = rows.isEmpty() ? null : rows.get(0);
        boolean matches = encoder.matches(r.password(), u == null ? dummyHash : (String) u.get("password_hash"));
        Instant lockedUntil = u == null || u.get("locked_until") == null ? null : ((java.sql.Timestamp) u.get("locked_until")).toInstant();
        if (u != null && lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
            audit.write((UUID) u.get("id"), email, ip, "ADMIN_LOGIN_BLOCKED", "user", u.get("id"), null, false, "account temporarily locked", null);
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again later.");
        }
        if (u == null || !matches || !(Boolean) u.get("active")) {
            if (u != null) {
                int n = ((Number) u.get("failed_logins")).intValue() + 1;
                jdbc.update("update users set failed_logins = ?, locked_until = ? where id = ?", n >= MAX_FAILURES ? 0 : n,
                    n >= MAX_FAILURES ? java.sql.Timestamp.from(Instant.now().plusSeconds(LOCK_MINUTES * 60)) : null, u.get("id"));
            }
            audit.write(u == null ? null : (UUID) u.get("id"), email, ip, "ADMIN_LOGIN_FAILED", "user", u == null ? null : u.get("id"), null, false, null, null);
            throw ApiException.unauthorized("Email or password is incorrect");   // same message for unknown email, wrong password and disabled account
        }
        UUID userId = (UUID) u.get("id");
        UUID sid = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(sessionMinutes * 60);
        jdbc.update("update users set failed_logins = 0, locked_until = null, last_login_at = now() where id = ?", userId);
        jdbc.update("insert into admin_sessions (id, user_id, expires_at, ip, user_agent) values (?,?,?,?,?)", sid, userId, java.sql.Timestamp.from(expires), ip, cut(req.getHeader("User-Agent"), 300));
        audit.write(userId, email, ip, "ADMIN_LOGIN", "user", userId, null, true, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", jwt.createAdminToken(userId, (String) u.get("email"), sid, expires));
        out.put("expiresAt", expires.toString());
        out.put("mustChangePassword", u.get("must_change_password"));
        out.put("email", u.get("email"));
        out.put("fullName", u.get("full_name"));
        return out;
    }

    @PostMapping("/logout")
    @PreAuthorize("@superAdmin.any()")
    @Transactional
    public Map<String, Boolean> logout() {
        SuperAdminAccess.Admin a = access.current();
        jdbc.update("update admin_sessions set revoked_at = now() where id = ? and revoked_at is null", a.sessionId());
        audit.ok("ADMIN_LOGOUT", "user", a.userId(), null, null, null);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    @PreAuthorize("@superAdmin.any()")
    public Map<String, Object> me() {
        SuperAdminAccess.Admin a = access.current();
        var row = AdminSql.one(jdbc, "select email, full_name, last_login_at, password_changed_at from users where id = ?", a.userId());
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("mustChangePassword", a.mustChangePassword());
        out.put("id", a.userId());
        return out;
    }

    @PostMapping("/change-password")
    @PreAuthorize("@superAdmin.any()")
    @Transactional
    public Map<String, Object> changePassword(@Valid @RequestBody ChangePasswordRequest r) {
        SuperAdminAccess.Admin a = access.current();
        String hash = jdbc.queryForObject("select password_hash from users where id = ?", String.class, a.userId());
        if (!encoder.matches(r.currentPassword(), hash)) {
            audit.record("ADMIN_PASSWORD_CHANGE", "user", a.userId(), null, false, "current password was wrong", null);
            throw ApiException.badRequest("The current password is not correct");
        }
        String weak = SuperAdminProvisioner.weakness(r.newPassword());
        if (weak != null) throw ApiException.badRequest("Choose a stronger password: " + weak);
        if (r.newPassword().equals(r.currentPassword())) throw ApiException.badRequest("Choose a password you have not used just now");
        jdbc.update("update users set password_hash = ?, must_change_password = false, password_changed_at = now() where id = ?", encoder.encode(r.newPassword()), a.userId());
        int ended = jdbc.update("update admin_sessions set revoked_at = now() where user_id = ? and id <> ? and revoked_at is null", a.userId(), a.sessionId());
        audit.ok("ADMIN_PASSWORD_CHANGE", "user", a.userId(), null, null, Map.of("otherSessionsEnded", ended));
        return Map.of("ok", true, "otherSessionsEnded", ended);
    }

    private static String cut(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }
}
