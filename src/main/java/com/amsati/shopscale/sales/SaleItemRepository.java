package com.amsati.shopscale.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleItemRepository extends JpaRepository<SaleItem, UUID> {

    interface TopProduct {
        String getName();
        java.math.BigDecimal getQuantity();
        BigDecimal getRevenue();
    }

    interface CategoryTotal {
        String getName();
        BigDecimal getRevenue();
    }

    @Query("select c.name as name, sum(i.lineTotal * (i.quantity - i.quantityRefunded) / i.quantity) as revenue from SaleItem i "
        + "join ProductVariant v on v.id = i.variantId join Product p on p.id = v.product.id "
        + "left join Category c on c.id = p.categoryId "
        + "where i.businessId = :b and i.sale.status = :status and i.sale.createdAt >= :from and i.sale.createdAt < :to "
        + "group by c.name order by sum(i.lineTotal * (i.quantity - i.quantityRefunded) / i.quantity) desc")
    List<CategoryTotal> byCategory(@Param("b") UUID b, @Param("status") SaleStatus status,
                                   @Param("from") Instant from, @Param("to") Instant to);

    interface Units {
        UUID getVariantId();
        java.math.BigDecimal getUnits();
    }

    @Query("select i.variantId as variantId, sum(i.quantity - i.quantityRefunded) as units from SaleItem i where i.businessId = :b "
        + "and i.sale.status = :status and i.sale.createdAt >= :from group by i.variantId")
    List<Units> unitsSoldByVariant(@Param("b") UUID b, @Param("status") SaleStatus status, @Param("from") Instant from);

    List<SaleItem> findBySaleId(UUID saleId);

    @Query("select i.productName as name, sum(i.quantity - i.quantityRefunded) as quantity, sum(i.lineTotal * (i.quantity - i.quantityRefunded) / i.quantity) as revenue "
        + "from SaleItem i where i.businessId = :b and i.sale.status = :status and i.sale.createdAt >= :from "
        + "and i.sale.createdAt < :to group by i.productName order by sum(i.quantity - i.quantityRefunded) desc")
    List<TopProduct> topProducts(@Param("b") UUID b, @Param("status") SaleStatus status,
                                 @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** Best sellers by revenue (not by profit), refunds taken off. */
    @Query("select i.productName as name, sum(i.quantity - i.quantityRefunded) as quantity, sum(i.lineTotal * (i.quantity - i.quantityRefunded) / i.quantity) as revenue "
        + "from SaleItem i where i.businessId = :b and i.sale.status = :status and i.sale.createdAt >= :from "
        + "and i.sale.createdAt < :to group by i.productName order by sum(i.lineTotal * (i.quantity - i.quantityRefunded) / i.quantity) desc")
    List<TopProduct> topByRevenue(@Param("b") UUID b, @Param("status") SaleStatus status,
                                  @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** Items sold in the period with no cost price recorded, so profit cannot be exact. */
    @Query("select count(i) from SaleItem i where i.businessId = :b and i.sale.status = :status and i.sale.createdAt >= :from and i.sale.createdAt < :to "
        + "and (i.unitCost is null or i.unitCost = 0)")
    long withoutCost(@Param("b") UUID b, @Param("status") SaleStatus status, @Param("from") Instant from, @Param("to") Instant to);
}
