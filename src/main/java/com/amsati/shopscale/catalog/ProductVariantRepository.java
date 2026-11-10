package com.amsati.shopscale.catalog;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {
    interface StockHealth {
        Long getOutOfStock();
        Long getLow();
        Long getHealthy();
    }

    interface SlowItem {
        String getName();
        String getLabel();
        java.math.BigDecimal getQuantity();
    }

    @Query("select sum(case when v.quantity <= 0 then 1 else 0 end) as outOfStock, "
        + "sum(case when v.quantity > 0 and v.quantity <= v.lowStockThreshold then 1 else 0 end) as low, "
        + "sum(case when v.quantity > v.lowStockThreshold then 1 else 0 end) as healthy "
        + "from ProductVariant v join v.product p where v.businessId = :b and v.active = true and p.status = :status")
    StockHealth stockHealth(@Param("b") UUID b, @Param("status") ProductStatus status);

    /** In stock but with no completed sale in the period. */
    @Query("select p.name as name, v.label as label, v.quantity as quantity from ProductVariant v join v.product p "
        + "where v.businessId = :b and v.active = true and p.status = :status and v.quantity > 0 and v.id not in "
        + "(select i.variantId from SaleItem i where i.businessId = :b and i.sale.status = :sold "
        + "and i.sale.createdAt >= :from and i.sale.createdAt < :to) order by v.quantity desc")
    List<SlowItem> slowMoving(@Param("b") UUID b, @Param("status") ProductStatus status,
                              @Param("sold") com.amsati.shopscale.sales.SaleStatus sold,
                              @Param("from") java.time.Instant from, @Param("to") java.time.Instant to, Pageable pageable);

    @Query("select v from ProductVariant v join fetch v.product p where v.businessId = :b and v.active = true and p.status = :status")
    List<ProductVariant> activeWithProduct(@Param("b") UUID b, @Param("status") ProductStatus status);

    List<ProductVariant> findByProductIdIn(Collection<UUID> productIds);
    List<ProductVariant> findByProductId(UUID productId);
    Optional<ProductVariant> findByBusinessIdAndSkuIgnoreCase(UUID businessId, String sku);

    /** Row-locks variants (ordered by id to avoid deadlocks) so concurrent sales cannot oversell. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.businessId = :b and v.id in :ids order by v.id")
    List<ProductVariant> lockAll(@Param("b") UUID businessId, @Param("ids") Collection<UUID> ids);

    @Query("select v from ProductVariant v join fetch v.product p where v.businessId = :b and v.active = true "
        + "and p.status = :status and (lower(p.name) like :q or lower(v.label) like :q or lower(v.sku) like :q or lower(v.barcode) like :q) "
        + "order by p.name, v.label")
    List<ProductVariant> sellable(@Param("b") UUID b, @Param("status") ProductStatus status, @Param("q") String q, Pageable pageable);

    @Query("select sum(v.cost * v.quantity) from ProductVariant v join v.product p where v.businessId = :b "
        + "and v.active = true and v.quantity > 0 and p.status = :status")
    BigDecimal inventoryValue(@Param("b") UUID b, @Param("status") ProductStatus status);

    @Query("select count(v) from ProductVariant v join v.product p where v.businessId = :b and v.active = true "
        + "and p.status = :status and v.quantity <= v.lowStockThreshold")
    long lowStockCount(@Param("b") UUID b, @Param("status") ProductStatus status);

    @Query("select new com.amsati.shopscale.inventory.LowStockDto(v.id, p.name, v.label, v.quantity, v.lowStockThreshold) "
        + "from ProductVariant v join v.product p where v.businessId = :b and v.active = true and p.status = :status "
        + "and v.quantity <= v.lowStockThreshold order by v.quantity, p.name")
    List<com.amsati.shopscale.inventory.LowStockDto> lowStock(@Param("b") UUID b, @Param("status") ProductStatus status);
}
