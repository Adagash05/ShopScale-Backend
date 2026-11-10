package com.amsati.shopscale.notification;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notifications")
@Getter @Setter @NoArgsConstructor
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private String audiencePermission;
    private String type;
    private String title;
    private String body;
    private String link;
    private String dedupeKey;
    private Instant createdAt = Instant.now();
}
