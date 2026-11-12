package com.amsati.shopscale.subscription;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findByBusinessId(UUID businessId);

    /**
     * Subscriptions whose paid period has ended and that still have automatic renewal on, within the charge window (period end up to
     * the grace period). FOR UPDATE SKIP LOCKED lets several backend instances run the scheduler at once: each row is claimed by one.
     * Cancelled, expired and suspended subscriptions and the Free plan are never returned.
     */
    @Query(value = """
        select * from subscriptions s
        where s.auto_renew = true and s.status in ('ACTIVE', 'PAST_DUE') and s.plan_code <> 'FREE'
          and s.current_period_end <= :now and s.current_period_end > :windowStart
        order by s.current_period_end
        limit :max for update skip locked""", nativeQuery = true)
    List<Subscription> claimDue(@Param("now") Instant now, @Param("windowStart") Instant windowStart, @Param("max") int max);

    @Query(value = "select * from subscriptions s where s.id = :id for update", nativeQuery = true)
    Optional<Subscription> lockById(@Param("id") UUID id);
}
