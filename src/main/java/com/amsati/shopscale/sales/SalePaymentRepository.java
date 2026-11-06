package com.amsati.shopscale.sales;

import com.amsati.shopscale.common.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalePaymentRepository extends JpaRepository<SalePayment, UUID> {

    interface MethodTotal {
        PaymentMethod getMethod();
        BigDecimal getAmount();
    }

    List<SalePayment> findBySaleId(UUID saleId);

    @Query("select p.method as method, sum(p.amount) as amount from SalePayment p "
        + "where p.businessId = :b and p.sale.status = :status and p.sale.createdAt >= :from and p.sale.createdAt < :to "
        + "group by p.method")
    List<MethodTotal> breakdown(@Param("b") UUID b, @Param("status") SaleStatus status,
                                @Param("from") Instant from, @Param("to") Instant to);
}
