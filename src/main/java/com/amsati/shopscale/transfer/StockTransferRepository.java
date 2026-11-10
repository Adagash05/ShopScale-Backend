package com.amsati.shopscale.transfer;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockTransferRepository extends JpaRepository<StockTransfer, UUID> {
    Optional<StockTransfer> findByIdAndBusinessId(UUID id, UUID businessId);
    Optional<StockTransfer> findByBusinessIdAndClientRequestId(UUID businessId, String clientRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from StockTransfer t where t.id = :id and t.businessId = :b")
    Optional<StockTransfer> findForUpdate(@Param("id") UUID id, @Param("b") UUID businessId);

    Page<StockTransfer> findByBusinessIdOrderByCreatedAtDesc(UUID businessId, Pageable pageable);
}
