package com.amsati.shopscale.purchasing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, UUID> {
    List<SupplierPayment> findTop50ByBusinessIdAndSupplierIdOrderByCreatedAtDesc(UUID businessId, UUID supplierId);
}
