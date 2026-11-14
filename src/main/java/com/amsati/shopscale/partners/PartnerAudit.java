package com.amsati.shopscale.partners;

import com.amsati.shopscale.security.AuthPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Append-only administrative audit trail for the programme (the table refuses updates and deletes). */
@Component
@RequiredArgsConstructor
public class PartnerAudit {
    private final JdbcTemplate jdbc;

    /** Records the signed-in person (or the system when there is none) as the actor. */
    public void record(String action, String entity, UUID entityId, String detail) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        UUID actor = a != null && a.getPrincipal() instanceof AuthPrincipal p ? p.userId() : null;
        recordAs(actor, action, entity, entityId, detail);
    }

    public void recordAs(UUID actor, String action, String entity, UUID entityId, String detail) {
        jdbc.update("insert into affiliate_audit_log (id, actor_user_id, action, entity, entity_id, detail) values (?,?,?,?,?,?)",
            UUID.randomUUID(), actor, action, entity, entityId, detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000)));
    }
}
