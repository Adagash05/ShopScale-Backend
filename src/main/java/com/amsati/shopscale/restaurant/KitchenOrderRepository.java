package com.amsati.shopscale.restaurant;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KitchenOrderRepository extends JpaRepository<KitchenOrder, UUID> {
    Optional<KitchenOrder> findByIdAndBusinessId(UUID id, UUID businessId);
    List<KitchenOrder> findByBusinessIdAndStatusOrderByCreatedAt(UUID businessId, String status);
    List<KitchenOrder> findTop50ByBusinessIdOrderByCreatedAtDesc(UUID businessId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from KitchenOrder o where o.id = :id and o.businessId = :b")
    Optional<KitchenOrder> lockOne(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("b") UUID businessId);
}
