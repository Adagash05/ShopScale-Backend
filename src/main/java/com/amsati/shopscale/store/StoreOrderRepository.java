package com.amsati.shopscale.store;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface StoreOrderRepository extends JpaRepository<StoreOrder, UUID> {
    Optional<StoreOrder> findByIdAndBusinessId(UUID id, UUID businessId);
    Optional<StoreOrder> findByPublicToken(String token);
    Optional<StoreOrder> findByPaystackReference(String reference);
    List<StoreOrder> findTop100ByBusinessIdOrderByCreatedAtDesc(UUID businessId);
    List<StoreOrder> findTop100ByBusinessIdAndStatusOrderByCreatedAtDesc(UUID businessId, String status);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StoreOrder o where o.id = :id")
    Optional<StoreOrder> lockById(@Param("id") UUID id);
    long countByBusinessIdAndStatus(UUID businessId, String status);
}
