package com.amsati.shopscale.purchasing;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {
    Optional<Supplier> findByIdAndBusinessId(UUID id, UUID businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Supplier s where s.id = :id and s.businessId = :b")
    Optional<Supplier> findForUpdate(@Param("id") UUID id, @Param("b") UUID businessId);

    @Query("select s from Supplier s where s.businessId = :b and lower(s.name) like :q order by s.name")
    Page<Supplier> search(@Param("b") UUID b, @Param("q") String q, Pageable pageable);

    @Query("select sum(s.balance) from Supplier s where s.businessId = :b and s.balance > 0")
    BigDecimal totalOwed(@Param("b") UUID b);
}
