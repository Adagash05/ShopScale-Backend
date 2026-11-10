package com.amsati.shopscale.store;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface StoreOrderItemRepository extends JpaRepository<StoreOrderItem, UUID> {
    List<StoreOrderItem> findByOrderId(UUID orderId);
    List<StoreOrderItem> findByOrderIdIn(Collection<UUID> orderIds);
}
