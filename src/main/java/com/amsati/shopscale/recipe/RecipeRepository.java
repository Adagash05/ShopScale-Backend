package com.amsati.shopscale.recipe;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipeRepository extends JpaRepository<Recipe, UUID> {
    Optional<Recipe> findByBusinessIdAndVariantId(UUID businessId, UUID variantId);
    List<Recipe> findByBusinessIdAndVariantIdInAndMode(UUID businessId, Collection<UUID> variantIds, String mode);
    List<Recipe> findByBusinessId(UUID businessId);
}
