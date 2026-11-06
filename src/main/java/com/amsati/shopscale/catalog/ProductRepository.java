package com.amsati.shopscale.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    Optional<Product> findByIdAndBusinessId(UUID id, UUID businessId);
    long countByBusinessIdAndStatus(UUID businessId, ProductStatus status);

    String MATCH = "(lower(p.name) like :q or p.id in (select v.product.id from ProductVariant v "
        + "where v.businessId = :b and (lower(v.sku) like :q or lower(v.barcode) like :q)))";

    @Query("select p from Product p where p.businessId = :b and p.status = :status and " + MATCH + " order by p.name")
    Page<Product> search(@Param("b") UUID b, @Param("status") ProductStatus status, @Param("q") String q, Pageable pageable);

    @Query("select p from Product p where p.businessId = :b and p.status = :status and p.categoryId = :cat and " + MATCH + " order by p.name")
    Page<Product> searchInCategory(@Param("b") UUID b, @Param("status") ProductStatus status,
                                   @Param("cat") UUID categoryId, @Param("q") String q, Pageable pageable);
}
