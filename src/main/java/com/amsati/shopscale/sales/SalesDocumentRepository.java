package com.amsati.shopscale.sales;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesDocumentRepository extends JpaRepository<SalesDocument, UUID> {
    Optional<SalesDocument> findByIdAndBusinessId(UUID id, UUID businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from SalesDocument d where d.id = :id and d.businessId = :b")
    Optional<SalesDocument> findForUpdate(@Param("id") UUID id, @Param("b") UUID businessId);

    Page<SalesDocument> findByBusinessIdAndKindOrderByCreatedAtDesc(UUID businessId, String kind, Pageable pageable);
}
