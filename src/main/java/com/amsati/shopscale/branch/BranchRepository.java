package com.amsati.shopscale.branch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BranchRepository extends JpaRepository<Branch, UUID> {
    Optional<Branch> findByIdAndBusinessId(UUID id, UUID businessId);

    List<Branch> findByBusinessIdOrderByMainDescNameAsc(UUID businessId);

    @Query("select b from Branch b where b.businessId = :bid and b.main = true")
    Optional<Branch> findMain(@Param("bid") UUID businessId);

    long countByBusinessIdAndActiveTrue(UUID businessId);

    boolean existsByBusinessIdAndNameIgnoreCase(UUID businessId, String name);
}
