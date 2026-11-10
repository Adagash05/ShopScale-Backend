package com.amsati.shopscale.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationReadRepository extends JpaRepository<NotificationRead, NotificationRead.Key> {
    @Query("select r.notificationId from NotificationRead r where r.userId = :u and r.notificationId in :ids")
    List<UUID> readIds(@Param("u") UUID userId, @Param("ids") Collection<UUID> ids);
}
