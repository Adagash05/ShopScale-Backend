package com.amsati.shopscale.branch;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BranchStockRepository extends JpaRepository<BranchStock, BranchStock.Key> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BranchStock s where s.id.branchId = :branch and s.id.variantId = :variant and s.businessId = :b")
    Optional<BranchStock> lockOne(@Param("b") UUID businessId, @Param("branch") UUID branchId, @Param("variant") UUID variantId);

    @Query("select s from BranchStock s where s.businessId = :b and s.id.variantId = :variant")
    List<BranchStock> forVariant(@Param("b") UUID businessId, @Param("variant") UUID variantId);

    @Query("select s from BranchStock s where s.businessId = :b and s.id.branchId = :branch")
    List<BranchStock> forBranch(@Param("b") UUID businessId, @Param("branch") UUID branchId);

    @Query("select coalesce(sum(s.quantity), 0) from BranchStock s where s.businessId = :b and s.id.branchId = :branch")
    BigDecimal unitsAtBranch(@Param("b") UUID businessId, @Param("branch") UUID branchId);
}
