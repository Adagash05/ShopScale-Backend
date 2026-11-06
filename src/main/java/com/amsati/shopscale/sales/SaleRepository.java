package com.amsati.shopscale.sales;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleRepository extends JpaRepository<Sale, UUID> {

    interface SalesAggregate {
        BigDecimal getTotal();
        BigDecimal getCost();
        BigDecimal getDiscount();
        Long getSaleCount();
    }

    interface DayAggregate {
        String getDay();
        BigDecimal getTotal();
        BigDecimal getCost();
    }

    /** Sales per local calendar day in the business timezone. */
    @Query(value = "select to_char((s.created_at at time zone :tz), 'YYYY-MM-DD') as day, sum(s.total) as total, sum(s.cost_total) as cost "
        + "from sales s where s.business_id = :b and s.status = 'COMPLETED' and s.created_at >= :from and s.created_at < :to "
        + "group by 1 order by 1", nativeQuery = true)
    List<DayAggregate> daily(@Param("b") UUID b, @Param("tz") String tz, @Param("from") Instant from, @Param("to") Instant to);

    interface LastCredit {
        UUID getCustomerId();
        Instant getAt();
    }

    @Query("select s.customerId as customerId, max(s.createdAt) as at from Sale s where s.businessId = :b "
        + "and s.status = :status and s.amountOwed > 0 and s.customerId is not null group by s.customerId")
    List<LastCredit> lastCreditSales(@Param("b") UUID b, @Param("status") SaleStatus status);

    Optional<Sale> findByIdAndBusinessId(UUID id, UUID businessId);
    Optional<Sale> findByBusinessIdAndClientRequestId(UUID businessId, String clientRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sale s where s.id = :id and s.businessId = :b")
    Optional<Sale> findForUpdate(@Param("id") UUID id, @Param("b") UUID businessId);

    String SUMMARY = "select new com.amsati.shopscale.sales.SaleSummary(s.id, s.saleNumber, s.status, c.name, s.total, "
        + "s.amountPaid, s.amountOwed, s.createdAt) from Sale s left join Customer c on c.id = s.customerId ";

    @Query(value = SUMMARY + "where s.businessId = :b and s.createdAt >= :from and s.createdAt < :to order by s.createdAt desc",
        countQuery = "select count(s) from Sale s where s.businessId = :b and s.createdAt >= :from and s.createdAt < :to")
    Page<SaleSummary> search(@Param("b") UUID b, @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    @Query(value = SUMMARY + "where s.businessId = :b and s.customerId = :customer order by s.createdAt desc",
        countQuery = "select count(s) from Sale s where s.businessId = :b and s.customerId = :customer")
    Page<SaleSummary> forCustomer(@Param("b") UUID b, @Param("customer") UUID customerId, Pageable pageable);

    @Query(value = SUMMARY + "where s.businessId = :b and s.createdBy = :user and s.createdAt >= :from and s.createdAt < :to order by s.createdAt desc",
        countQuery = "select count(s) from Sale s where s.businessId = :b and s.createdBy = :user and s.createdAt >= :from and s.createdAt < :to")
    Page<SaleSummary> searchMine(@Param("b") UUID b, @Param("user") UUID user, @Param("from") Instant from,
                                 @Param("to") Instant to, Pageable pageable);

    @Query("select sum(s.total) as total, sum(s.costTotal) as cost, sum(s.discount) as discount, count(s) as saleCount "
        + "from Sale s where s.businessId = :b and s.status = :status and s.createdAt >= :from and s.createdAt < :to")
    SalesAggregate aggregate(@Param("b") UUID b, @Param("status") SaleStatus status,
                             @Param("from") Instant from, @Param("to") Instant to);
}
