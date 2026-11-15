package com.amsati.shopscale.superadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The administrative audit trail. The table refuses updates, deletes and truncation (database triggers), and nothing in the application edits it.
 * Never pass passwords, tokens or secrets in {@code metadata}.
 */
@Component
public class AdminAudit {
    private static final Logger log = LoggerFactory.getLogger(AdminAudit.class);
    private final JdbcTemplate jdbc;
    private final SuperAdminAccess access;
    private final ObjectMapper json = new ObjectMapper();

    public AdminAudit(JdbcTemplate jdbc, SuperAdminAccess access) { this.jdbc = jdbc; this.access = access; }

    /** Records the current Super Admin as the actor. */
    public void record(String action, String targetType, Object targetId, UUID businessId, boolean success, String reason, Map<String, ?> metadata) {
        SuperAdminAccess.Admin a = access.current();
        write(a.userId(), a.email(), a.ip(), action, targetType, targetId, businessId, success, reason, metadata);
    }

    public void ok(String action, String targetType, Object targetId, UUID businessId, String reason, Map<String, ?> metadata) {
        record(action, targetType, targetId, businessId, true, reason, metadata);
    }

    /** For events with no signed-in admin, such as a failed sign-in. */
    public void write(UUID actorId, String actorEmail, String ip, String action, String targetType, Object targetId, UUID businessId, boolean success, String reason, Map<String, ?> metadata) {
        String meta = null;
        try { meta = metadata == null || metadata.isEmpty() ? null : json.writeValueAsString(metadata); } catch (Exception ignored) { /* audit must not break the action */ }
        try {
            jdbc.update("insert into admin_audit_log (id, actor_id, actor_email, action, target_type, target_id, business_id, success, reason, ip, metadata) values (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), actorId, cut(actorEmail, 255), cut(action, 60), cut(targetType, 40), targetId == null ? null : cut(targetId.toString(), 80), businessId, success, cut(reason, 500), cut(ip, 64), cut(meta, 4000));
        } catch (RuntimeException e) {
            // A sensitive action must not proceed unrecorded: callers run this inside their transaction, so the failure rolls the action back.
            log.error("Could not write the admin audit record for {}: {}", action, e.toString());
            throw e;
        }
    }

    private static String cut(String s, int max) { return s == null ? null : s.length() > max ? s.substring(0, max) : s; }
}
