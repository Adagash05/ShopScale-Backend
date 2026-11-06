package com.amsati.shopscale.audit;

import com.amsati.shopscale.auth.User;
import com.amsati.shopscale.auth.UserRepository;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The business activity feed: a read of real, persisted audit events. Nothing here is generated for display. */
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {
    private final AuditLogRepository logs;
    private final UserRepository users;
    private final AccessService access;

    public record ActivityDto(UUID id, String action, String entity, String entityId, String detail, String userName, Instant createdAt) {}

    @GetMapping
    @PreAuthorize("@access.has('DASHBOARD_VIEW')")
    @Transactional(readOnly = true)
    public List<ActivityDto> recent(@RequestParam(defaultValue = "20") int size) {
        List<AuditLog> rows = logs.findByBusinessIdOrderByCreatedAtDesc(Tenant.businessId(), PageRequest.of(0, Math.min(Math.max(size, 1), 50)));
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> { if (r.getUserId() != null) ids.add(r.getUserId()); });
        Map<UUID, String> names = users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, User::getFullName));
        boolean showAmounts = access.current().contains(Permission.DASHBOARD_FINANCIAL);
        return rows.stream().map(r -> new ActivityDto(r.getId(), r.getAction(), r.getEntity(), r.getEntityId(),
            showAmounts ? r.getDetail() : null, r.getUserId() == null ? null : names.get(r.getUserId()), r.getCreatedAt())).toList();
    }
}
