package com.amsati.shopscale.sales;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleRefundItemRepository extends JpaRepository<SaleRefundItem, UUID> {
    List<SaleRefundItem> findByRefundId(UUID refundId);
}
