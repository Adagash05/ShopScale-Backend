package com.amsati.shopscale.sales;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleRefundRepository extends JpaRepository<SaleRefund, UUID> {
    List<SaleRefund> findBySaleIdAndBusinessIdOrderByCreatedAtAsc(UUID saleId, UUID businessId);
}
