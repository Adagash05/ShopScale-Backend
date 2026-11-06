package com.amsati.shopscale.audit;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "audit_logs")
@Getter @Setter @NoArgsConstructor
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID userId;
    private String action;
    private String entity;
    private String entityId;
    private String detail;
    private Instant createdAt = Instant.now();
}
