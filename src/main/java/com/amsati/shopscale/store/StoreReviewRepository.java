package com.amsati.shopscale.store;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreReviewRepository extends JpaRepository<StoreReview, UUID> {
    boolean existsByOrderIdAndProductId(UUID orderId, UUID productId);
    List<StoreReview> findTop200ByBusinessIdAndProductIdAndStatusOrderByCreatedAtDesc(UUID businessId, UUID productId, String status);
    List<StoreReview> findTop12ByBusinessIdAndStatusAndRatingGreaterThanEqualOrderByCreatedAtDesc(UUID businessId, String status, int rating);
    List<StoreReview> findTop200ByBusinessIdOrderByCreatedAtDesc(UUID businessId);
    Optional<StoreReview> findByIdAndBusinessId(UUID id, UUID businessId);
    @Query("select r.productId, count(r), avg(r.rating) from StoreReview r where r.businessId = :b and r.status = 'APPROVED' group by r.productId")
    List<Object[]> summary(@Param("b") UUID businessId);
}
