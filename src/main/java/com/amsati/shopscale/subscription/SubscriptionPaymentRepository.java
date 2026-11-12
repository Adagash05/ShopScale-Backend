package com.amsati.shopscale.subscription;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, UUID> {
    Optional<SubscriptionPayment> findByReference(String reference);
    Optional<SubscriptionPayment> findByPaystackTransaction(String paystackTransaction);
    List<SubscriptionPayment> findTop50ByBusinessIdOrderByCreatedAtDesc(UUID businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SubscriptionPayment p where p.id = :id")
    Optional<SubscriptionPayment> lockById(@Param("id") UUID id);
}
