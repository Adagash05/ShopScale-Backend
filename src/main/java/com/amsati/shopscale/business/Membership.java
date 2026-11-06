package com.amsati.shopscale.business;

import com.amsati.shopscale.security.Role;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "memberships")
@Getter @Setter @NoArgsConstructor
public class Membership {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID userId;
    private UUID businessId;
    @Enumerated(EnumType.STRING)
    private Role role;
    private UUID roleId;
    private UUID invitedBy;
    private Instant lastActiveAt;
    private boolean active = true;
    private Instant createdAt = Instant.now();
}
