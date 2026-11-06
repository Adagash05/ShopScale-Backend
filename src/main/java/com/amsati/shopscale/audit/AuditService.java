package com.amsati.shopscale.audit;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repo;

    /** Joins the caller's transaction so the audit entry commits or rolls back with the change. */
    public void record(UUID businessId, UUID userId, String action, String entity, Object entityId, String detail) {
        AuditLog a = new AuditLog();
        a.setBusinessId(businessId);
        a.setUserId(userId);
        a.setAction(action);
        a.setEntity(entity);
        a.setEntityId(entityId == null ? null : entityId.toString());
        a.setDetail(detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail));
        repo.save(a);
    }
}
