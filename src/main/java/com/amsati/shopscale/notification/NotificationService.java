package com.amsati.shopscale.notification;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications. Producers (stock, expenses, orders, billing...) call {@link #publish} from inside their own
 * transaction, so a notification exists exactly when the thing it reports was saved. A dedupe key makes repeated
 * triggers (for example the same low-stock item on one day) produce one notification.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notifications;
    private final NotificationReadRepository reads;
    private final AccessService access;

    public record NotificationDto(UUID id, String type, String title, String body, String link, Instant createdAt, boolean read) {}
    public record Inbox(long unread, List<NotificationDto> items) {}

    public void publish(UUID businessId, Permission audience, String type, String title, String body, String link, String dedupeKey) {
        if (dedupeKey != null && notifications.existsByBusinessIdAndDedupeKey(businessId, dedupeKey)) return;
        Notification n = new Notification();
        n.setBusinessId(businessId);
        n.setAudiencePermission(audience == null ? null : audience.name());
        n.setType(type);
        n.setTitle(trim(title, 200));
        n.setBody(trim(body, 500));
        n.setLink(link);
        n.setDedupeKey(dedupeKey);
        notifications.save(n);
    }

    private static String trim(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }

    @Transactional(readOnly = true)
    public Inbox inbox(int size) {
        UUID bid = Tenant.businessId();
        Set<Permission> mine = access.current();
        List<Notification> rows = notifications.findByBusinessIdOrderByCreatedAtDesc(bid, PageRequest.of(0, 100)).stream()
            .filter(n -> n.getAudiencePermission() == null || has(mine, n.getAudiencePermission())).toList();
        Set<UUID> read = new HashSet<>(rows.isEmpty() ? List.of() : reads.readIds(Tenant.userId(), rows.stream().map(Notification::getId).toList()));
        long unread = rows.stream().filter(n -> !read.contains(n.getId())).count();
        List<NotificationDto> items = rows.stream().limit(Math.min(Math.max(size, 1), 100))
            .map(n -> new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLink(), n.getCreatedAt(), read.contains(n.getId()))).toList();
        return new Inbox(unread, items);
    }

    private static boolean has(Set<Permission> mine, String name) {
        try { return mine.contains(Permission.valueOf(name)); } catch (IllegalArgumentException e) { return false; }
    }

    @Transactional
    public void markRead(UUID id) {
        UUID bid = Tenant.businessId();
        Notification n = notifications.findById(id).filter(x -> x.getBusinessId().equals(bid)).orElseThrow(() -> ApiException.notFound("Notification not found"));
        if (n.getAudiencePermission() != null && !has(access.current(), n.getAudiencePermission())) throw ApiException.notFound("Notification not found");
        if (!reads.existsById(new NotificationRead.Key(id, Tenant.userId()))) reads.save(new NotificationRead(id, Tenant.userId()));
    }

    @Transactional
    public void markAllRead() {
        for (NotificationDto n : inbox(100).items()) if (!n.read()) markRead(n.id());
    }
}
