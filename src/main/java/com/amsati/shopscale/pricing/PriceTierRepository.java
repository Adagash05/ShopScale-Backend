package com.amsati.shopscale.pricing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceTierRepository extends JpaRepository<PriceTier, UUID> {
    List<PriceTier> findByBusinessIdOrderByName(UUID businessId);
    Optional<PriceTier> findByIdAndBusinessId(UUID id, UUID businessId);
    boolean existsByBusinessIdAndNameIgnoreCase(UUID businessId, String name);
}
