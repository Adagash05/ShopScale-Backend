package com.amsati.shopscale.pricing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VariantPriceRepository extends JpaRepository<VariantPrice, UUID> {
    @Query("select p from VariantPrice p where p.businessId = :b and p.variantId in :ids")
    List<VariantPrice> forVariants(@Param("b") UUID businessId, @Param("ids") Collection<UUID> ids);

    @Query("select p from VariantPrice p where p.businessId = :b and p.variantId = :v order by p.minQuantity")
    List<VariantPrice> forVariant(@Param("b") UUID businessId, @Param("v") UUID variantId);

    Optional<VariantPrice> findByIdAndBusinessId(UUID id, UUID businessId);

    long countByBusinessIdAndPriceTierId(UUID businessId, UUID tierId);
}
