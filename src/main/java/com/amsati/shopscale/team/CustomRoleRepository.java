package com.amsati.shopscale.team;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomRoleRepository extends JpaRepository<CustomRole, UUID> {
    List<CustomRole> findByBusinessIdOrderByNameAsc(UUID businessId);
    Optional<CustomRole> findByIdAndBusinessId(UUID id, UUID businessId);
    boolean existsByBusinessIdAndNameIgnoreCase(UUID businessId, String name);
}
