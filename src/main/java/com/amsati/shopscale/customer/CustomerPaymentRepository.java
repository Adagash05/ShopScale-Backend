package com.amsati.shopscale.customer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerPaymentRepository extends JpaRepository<CustomerPayment, UUID> {
    interface LastPayment {
        UUID getCustomerId();
        Instant getAt();
    }

    @Query("select p.customerId as customerId, max(p.createdAt) as at from CustomerPayment p where p.businessId = :b group by p.customerId")
    List<LastPayment> lastPayments(@Param("b") UUID b);

    List<CustomerPayment> findTop50ByBusinessIdAndCustomerIdOrderByCreatedAtDesc(UUID businessId, UUID customerId);

    @Query("select sum(p.amount) from CustomerPayment p where p.businessId = :b and p.createdAt >= :from and p.createdAt < :to")
    BigDecimal sumBetween(@Param("b") UUID b, @Param("from") Instant from, @Param("to") Instant to);
}
