package com.amsati.shopscale.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "auth_tokens")
@Getter @Setter @NoArgsConstructor
public class AuthToken {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID userId;
    private String kind;
    private String tokenHash;
    private Instant expiresAt;
    private Instant usedAt;
    private Instant createdAt = Instant.now();
}
