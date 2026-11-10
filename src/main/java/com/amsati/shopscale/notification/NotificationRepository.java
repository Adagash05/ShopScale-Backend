package com.amsati.shopscale.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByBusinessIdOrderByCreatedAtDesc(UUID businessId, Pageable pageable);
    boolean existsByBusinessIdAndDedupeKey(UUID businessId, String dedupeKey);
}
