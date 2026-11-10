package com.amsati.shopscale.restaurant;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KitchenOrderItemRepository extends JpaRepository<KitchenOrderItem, UUID> {
    List<KitchenOrderItem> findByOrderIdOrderByCreatedAt(UUID orderId);
    List<KitchenOrderItem> findByOrderIdIn(Collection<UUID> orderIds);
    Optional<KitchenOrderItem> findByIdAndBusinessId(UUID id, UUID businessId);
}
