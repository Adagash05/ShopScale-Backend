package com.amsati.shopscale.unit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitConversionRepository extends JpaRepository<UnitConversion, UUID> {
    List<UnitConversion> findByBusinessIdAndVariantIdOrderByUnitAsc(UUID businessId, UUID variantId);
    Optional<UnitConversion> findByBusinessIdAndVariantIdAndUnit(UUID businessId, UUID variantId, String unit);
    Optional<UnitConversion> findByIdAndBusinessId(UUID id, UUID businessId);
}
