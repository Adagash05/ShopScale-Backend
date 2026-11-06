package com.amsati.shopscale.purchasing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseRepository extends JpaRepository<Purchase, UUID> {
    Optional<Purchase> findByIdAndBusinessId(UUID id, UUID businessId);
    Optional<Purchase> findByBusinessIdAndClientRequestId(UUID businessId, String clientRequestId);

    String SUMMARY = "select new com.amsati.shopscale.purchasing.PurchaseSummary(p.id, p.purchaseNumber, s.name, p.reference, "
        + "p.total, p.amountPaid, p.amountOwed, p.createdAt) from Purchase p left join Supplier s on s.id = p.supplierId ";

    @Query(value = SUMMARY + "where p.businessId = :b order by p.createdAt desc",
        countQuery = "select count(p) from Purchase p where p.businessId = :b")
    Page<PurchaseSummary> list(@Param("b") UUID b, Pageable pageable);

    @Query(value = SUMMARY + "where p.businessId = :b and p.supplierId = :supplier order by p.createdAt desc",
        countQuery = "select count(p) from Purchase p where p.businessId = :b and p.supplierId = :supplier")
    Page<PurchaseSummary> forSupplier(@Param("b") UUID b, @Param("supplier") UUID supplier, Pageable pageable);
}
