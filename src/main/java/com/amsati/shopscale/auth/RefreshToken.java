package com.amsati.shopscale.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "refresh_tokens")
@Getter @Setter @NoArgsConstructor
public class RefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID userId;
    private String tokenHash;
    private UUID familyId;
    private UUID businessId;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant rotatedAt;
    private String userAgent;
    private Instant sessionStartedAt;
    private Instant createdAt = Instant.now();
}
