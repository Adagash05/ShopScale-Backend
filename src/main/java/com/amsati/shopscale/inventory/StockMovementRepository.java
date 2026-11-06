package com.amsati.shopscale.inventory;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    @Query(value = "select new com.amsati.shopscale.inventory.MovementDto(m.id, v.id, p.name, v.label, m.type, "
        + "m.quantityChange, m.balanceAfter, m.note, m.createdAt) "
        + "from StockMovement m join ProductVariant v on v.id = m.variantId join Product p on p.id = v.product.id "
        + "where m.businessId = :b order by m.createdAt desc",
        countQuery = "select count(m) from StockMovement m where m.businessId = :b")
    Page<MovementDto> history(@Param("b") UUID businessId, Pageable pageable);

    @Query(value = "select new com.amsati.shopscale.inventory.MovementDto(m.id, v.id, p.name, v.label, m.type, "
        + "m.quantityChange, m.balanceAfter, m.note, m.createdAt) "
        + "from StockMovement m join ProductVariant v on v.id = m.variantId join Product p on p.id = v.product.id "
        + "where m.businessId = :b and m.variantId = :variant order by m.createdAt desc",
        countQuery = "select count(m) from StockMovement m where m.businessId = :b and m.variantId = :variant")
    Page<MovementDto> historyForVariant(@Param("b") UUID businessId, @Param("variant") UUID variantId, Pageable pageable);
}
