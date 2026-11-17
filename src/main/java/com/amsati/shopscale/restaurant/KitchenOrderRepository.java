package com.amsati.shopscale.restaurant;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KitchenOrderRepository extends JpaRepository<KitchenOrder, UUID> {
    Optional<KitchenOrder> findByIdAndBusinessId(UUID id, UUID businessId);
    List<KitchenOrder> findByBusinessIdAndStatusOrderByCreatedAt(UUID businessId, String status);
    List<KitchenOrder> findTop50ByBusinessIdOrderByCreatedAtDesc(UUID businessId);
    boolean existsByTableIdAndStatus(UUID tableId, String status);
    Optional<KitchenOrder> findByBusinessIdAndClientRequestId(UUID businessId, String clientRequestId);
    @org.springframework.data.jpa.repository.Query("select o from KitchenOrder o where o.businessId = :b and (o.status = 'OPEN' or (o.status = 'PAID' and exists "
        + "(select 1 from KitchenOrderItem i where i.orderId = o.id and i.status in ('NEW','PREPARING','READY')))) order by o.createdAt")
    List<KitchenOrder> findBoard(@org.springframework.data.repository.query.Param("b") UUID businessId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from KitchenOrder o where o.id = :id and o.businessId = :b")
    Optional<KitchenOrder> lockOne(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("b") UUID businessId);
}
