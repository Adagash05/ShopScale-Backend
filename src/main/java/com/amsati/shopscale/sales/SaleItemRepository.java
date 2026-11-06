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
        Long getQuantity();
        BigDecimal getRevenue();
    }

    interface CategoryTotal {
        String getName();
        BigDecimal getRevenue();
    }

    @Query("select c.name as name, sum(i.lineTotal) as revenue from SaleItem i "
        + "join ProductVariant v on v.id = i.variantId join Product p on p.id = v.product.id "
        + "left join Category c on c.id = p.categoryId "
        + "where i.businessId = :b and i.sale.status = :status and i.sale.createdAt >= :from and i.sale.createdAt < :to "
        + "group by c.name order by sum(i.lineTotal) desc")
    List<CategoryTotal> byCategory(@Param("b") UUID b, @Param("status") SaleStatus status,
                                   @Param("from") Instant from, @Param("to") Instant to);

    interface Units {
        UUID getVariantId();
        Long getUnits();
    }

    @Query("select i.variantId as variantId, sum(i.quantity) as units from SaleItem i where i.businessId = :b "
        + "and i.sale.status = :status and i.sale.createdAt >= :from group by i.variantId")
    List<Units> unitsSoldByVariant(@Param("b") UUID b, @Param("status") SaleStatus status, @Param("from") Instant from);

    List<SaleItem> findBySaleId(UUID saleId);

    @Query("select i.productName as name, sum(i.quantity) as quantity, sum(i.lineTotal) as revenue "
        + "from SaleItem i where i.businessId = :b and i.sale.status = :status and i.sale.createdAt >= :from "
        + "and i.sale.createdAt < :to group by i.productName order by sum(i.quantity) desc")
    List<TopProduct> topProducts(@Param("b") UUID b, @Param("status") SaleStatus status,
                                 @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
