package com.amsati.shopscale.superadmin;

import com.amsati.shopscale.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Non-secret platform settings. Anything sensitive (keys, tokens, storage credentials) stays in environment variables and is only
 * shown here as "configured / not configured", never as a value, and cannot be edited from a browser.
 */
@RestController

public class SuperAdminSettingsController {
    private final JdbcTemplate jdbc;
    private final AdminAudit audit;
    @Value("${spring.servlet.multipart.max-file-size:1MB}") private String maxUpload;

    public SuperAdminSettingsController(JdbcTemplate jdbc, AdminAudit audit) { this.jdbc = jdbc; this.audit = audit; }

    public record SettingsRequest(@Size(min = 1, max = 80) String platformName, @Size(max = 255) String supportEmail, @Size(max = 40) String supportPhone) {}

    private Map<String, String> load() {
        Map<String, String> m = new LinkedHashMap<>();
        jdbc.query("select key, value from platform_settings", rs -> { m.put(rs.getString(1), rs.getString(2)); });
        return m;
    }

    /** Public: the platform's name and support contact, for the apps to show. */
    @GetMapping("/api/public/platform-info")
    public Map<String, String> publicInfo() {
        Map<String, String> m = load();
        return Map.of("platformName", m.getOrDefault("platform_name", "ShopScale"), "supportEmail", m.getOrDefault("support_email", ""), "supportPhone", m.getOrDefault("support_phone", ""));
    }

    @GetMapping("/api/super-admin/settings")
    @PreAuthorize("@superAdmin.ok()")
    public Map<String, Object> get() {
        Map<String, String> m = load();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platformName", m.getOrDefault("platform_name", "ShopScale"));
        out.put("supportEmail", m.getOrDefault("support_email", ""));
        out.put("supportPhone", m.getOrDefault("support_phone", ""));
        out.put("uploadLimits", Map.of("requestMax", maxUpload, "productImageBytes", 600 * 1024, "logoBytes", 300 * 1024, "note", "Fixed in code and configuration; change through a deployment."));
        return out;
    }

    @PutMapping("/api/super-admin/settings")
    @PreAuthorize("@superAdmin.ok()")
    @Transactional
    public Map<String, Object> save(@Valid @RequestBody SettingsRequest r) {
        Map<String, String> before = load();
        Map<String, String> next = new LinkedHashMap<>();
        if (r.platformName() != null) next.put("platform_name", r.platformName().trim());
        if (r.supportEmail() != null) {
            String e = r.supportEmail().trim();
            if (!e.isEmpty() && !e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.badRequest("Enter a valid support email address");
            next.put("support_email", e);
        }
        if (r.supportPhone() != null) next.put("support_phone", r.supportPhone().trim());
        List<String> changed = new ArrayList<>();
        for (var e : next.entrySet()) if (!Objects.equals(before.get(e.getKey()), e.getValue())) {
            jdbc.update("insert into platform_settings (key, value, updated_at) values (?,?,now()) on conflict (key) do update set value = excluded.value, updated_at = now()", e.getKey(), e.getValue());
            changed.add(e.getKey());
        }
        audit.ok("PLATFORM_SETTINGS_CHANGED", "settings", "platform", null, null, Map.of("changed", changed));
        return get();
    }
}
