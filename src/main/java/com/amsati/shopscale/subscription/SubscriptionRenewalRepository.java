package com.amsati.shopscale.subscription;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRenewalRepository extends JpaRepository<SubscriptionRenewal, UUID> {
    List<SubscriptionRenewal> findBySubscriptionIdAndDueAtOrderByAttemptNoDesc(UUID subscriptionId, Instant dueAt);
    Optional<SubscriptionRenewal> findTop1ByBusinessIdOrderByCreatedAtDesc(UUID businessId);
    Optional<SubscriptionRenewal> findByPaymentId(UUID paymentId);
    List<SubscriptionRenewal> findByStatusAndCreatedAtBefore(String status, Instant before);
}
