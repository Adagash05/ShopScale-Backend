package com.amsati.shopscale.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    @org.springframework.data.jpa.repository.Query("select a from AuditLog a where a.businessId = :b and (:u is null or cast(a.userId as string) = :u) and (:e is null or a.entity = :e) and (:a is null or a.action = :a) order by a.createdAt desc")
    org.springframework.data.domain.Page<AuditLog> search(@org.springframework.data.repository.query.Param("b") UUID b, @org.springframework.data.repository.query.Param("u") String u,
        @org.springframework.data.repository.query.Param("e") String e, @org.springframework.data.repository.query.Param("a") String a, Pageable pageable);

    List<AuditLog> findByBusinessIdOrderByCreatedAtDesc(UUID businessId, Pageable pageable);
}
