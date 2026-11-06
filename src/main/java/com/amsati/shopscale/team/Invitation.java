package com.amsati.shopscale.team;

import com.amsati.shopscale.security.Role;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "invitations")
@Getter @Setter @NoArgsConstructor
public class Invitation {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private String email;
    private String fullName;
    private String phone;
    @Enumerated(EnumType.STRING)
    private Role role;
    private UUID roleId;
    private String tokenHash;
    private String status = "PENDING"; // PENDING, ACCEPTED, REVOKED, EXPIRED
    private Instant expiresAt;
    private UUID invitedBy;
    private boolean emailSent;
    private Instant createdAt = Instant.now();
    private Instant acceptedAt;
}
