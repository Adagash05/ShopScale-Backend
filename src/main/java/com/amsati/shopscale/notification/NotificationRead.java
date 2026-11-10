package com.amsati.shopscale.notification;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_reads")
@IdClass(NotificationRead.Key.class)
@Getter @Setter @NoArgsConstructor
public class NotificationRead {
    @Id private UUID notificationId;
    @Id private UUID userId;
    private Instant readAt = Instant.now();

    public NotificationRead(UUID notificationId, UUID userId) { this.notificationId = notificationId; this.userId = userId; }

    public record Key(UUID notificationId, UUID userId) implements Serializable {}
}
