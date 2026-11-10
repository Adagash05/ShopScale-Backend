package com.amsati.shopscale.restaurant;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductModifierGroupRepository extends JpaRepository<ProductModifierGroup, ProductModifierGroup.Key> {
    List<ProductModifierGroup> findByProductId(UUID productId);

    @Modifying
    @Query("delete from ProductModifierGroup l where l.productId = :p")
    void deleteByProduct(@Param("p") UUID productId);
}
