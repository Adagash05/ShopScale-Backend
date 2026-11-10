package com.amsati.shopscale.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SalesReportRepository extends Repository<Sale, UUID> {
    interface Row {
        Object getKey();
        Long getCount();
        BigDecimal getTotal();
        BigDecimal getCost();
    }

    @Query("select s.createdBy as key, count(s) as count, sum(s.total - s.refundedAmount) as total, sum(s.costTotal - s.refundedCost) as cost from Sale s "
        + "where s.businessId = :b and s.status = 'COMPLETED' and s.createdAt >= :from and s.createdAt < :to group by s.createdBy order by sum(s.total - s.refundedAmount) desc")
    List<Row> byEmployee(@Param("b") UUID b, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select s.branchId as key, count(s) as count, sum(s.total - s.refundedAmount) as total, sum(s.costTotal - s.refundedCost) as cost from Sale s "
        + "where s.businessId = :b and s.status = 'COMPLETED' and s.createdAt >= :from and s.createdAt < :to group by s.branchId order by sum(s.total - s.refundedAmount) desc")
    List<Row> byBranch(@Param("b") UUID b, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select s.channel as key, count(s) as count, sum(s.total - s.refundedAmount) as total, sum(s.costTotal - s.refundedCost) as cost from Sale s "
        + "where s.businessId = :b and s.status = 'COMPLETED' and s.createdAt >= :from and s.createdAt < :to group by s.channel order by sum(s.total - s.refundedAmount) desc")
    List<Row> byChannel(@Param("b") UUID b, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select p.method as key, count(distinct p.sale.id) as count, sum(p.amount) as total, sum(0) as cost from SalePayment p "
        + "where p.businessId = :b and p.sale.status = 'COMPLETED' and p.sale.createdAt >= :from and p.sale.createdAt < :to group by p.method order by sum(p.amount) desc")
    List<Row> byMethod(@Param("b") UUID b, @Param("from") Instant from, @Param("to") Instant to);
}
