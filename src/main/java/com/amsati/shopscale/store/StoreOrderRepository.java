package com.amsati.shopscale.store;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface StoreOrderRepository extends JpaRepository<StoreOrder, UUID> {
    Optional<StoreOrder> findByIdAndBusinessId(UUID id, UUID businessId);
    Optional<StoreOrder> findByPublicToken(String token);
    Optional<StoreOrder> findByBusinessIdAndNumber(UUID businessId, long number);
    Optional<StoreOrder> findByPaystackReference(String reference);
    List<StoreOrder> findTop100ByBusinessIdOrderByCreatedAtDesc(UUID businessId);
    List<StoreOrder> findTop100ByBusinessIdAndStatusOrderByCreatedAtDesc(UUID businessId, String status);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StoreOrder o where o.id = :id")
    Optional<StoreOrder> lockById(@Param("id") UUID id);
    /** Online orders still waiting for payment after {@code before}: the customer may have paid and never returned. */
    @Query("select o from StoreOrder o where o.status = 'PENDING_PAYMENT' and o.createdAt < :before order by o.createdAt")
    List<StoreOrder> stalePending(@Param("before") java.time.Instant before, org.springframework.data.domain.Pageable page);
    long countByBusinessIdAndStatus(UUID businessId, String status);
}
