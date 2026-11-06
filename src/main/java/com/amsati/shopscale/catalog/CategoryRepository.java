package com.amsati.shopscale.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByBusinessIdOrderByNameAsc(UUID businessId);
    Optional<Category> findByIdAndBusinessId(UUID id, UUID businessId);
    boolean existsByBusinessIdAndNameIgnoreCase(UUID businessId, String name);
}
