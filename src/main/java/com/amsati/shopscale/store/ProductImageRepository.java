package com.amsati.shopscale.store;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {
    java.util.List<ProductImage> findByBusinessIdAndProductIdAndGalleryTrueOrderByPositionAscCreatedAtAsc(UUID businessId, UUID productId);
    java.util.List<ProductImage> findByBusinessIdAndGalleryTrueOrderByPositionAscCreatedAtAsc(UUID businessId);
    Optional<ProductImage> findByIdAndBusinessId(UUID id, UUID businessId);
    @Query("select count(i) from ProductImage i where i.businessId = :b")
    long countFor(@Param("b") UUID businessId);
}
