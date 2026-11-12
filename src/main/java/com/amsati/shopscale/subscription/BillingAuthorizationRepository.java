package com.amsati.shopscale.subscription;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingAuthorizationRepository extends JpaRepository<BillingAuthorization, UUID> {
    Optional<BillingAuthorization> findByBusinessIdAndActiveTrue(UUID businessId);
}
