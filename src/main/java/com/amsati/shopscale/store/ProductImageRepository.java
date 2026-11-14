package com.amsati.shopscale.store;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {
    java.util.List<ProductImage> findByBusinessIdAndProductIdAndGalleryTrueOrderByPositionAscCreatedAtAsc(UUID businessId, UUID productId);
    java.util.List<ProductImage> findByBusinessIdAndGalleryTrueOrderByPositionAscCreatedAtAsc(UUID businessId);
    /** Ids only: the picture bytes are not loaded (the lazy byte[] is not bytecode-enhanced, so entity queries would read every blob). */
    interface GalleryRef { UUID getId(); UUID getProductId(); }
    @Query("select i.id as id, i.productId as productId from ProductImage i where i.businessId = :b and i.gallery = true order by i.position asc, i.createdAt asc")
    java.util.List<GalleryRef> galleryRefs(@Param("b") UUID businessId);
    Optional<ProductImage> findByIdAndBusinessId(UUID id, UUID businessId);
    @Query("select count(i) from ProductImage i where i.businessId = :b")
    long countFor(@Param("b") UUID businessId);
}
