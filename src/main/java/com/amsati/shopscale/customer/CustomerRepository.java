package com.amsati.shopscale.customer;

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

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    long countByBusinessId(java.util.UUID businessId);
    Optional<Customer> findByIdAndBusinessId(UUID id, UUID businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id and c.businessId = :b")
    Optional<Customer> findForUpdate(@Param("id") UUID id, @Param("b") UUID businessId);

    @Query("select c from Customer c where c.businessId = :b and (lower(c.name) like :q or lower(c.phone) like :q) order by c.name")
    Page<Customer> search(@Param("b") UUID b, @Param("q") String q, Pageable pageable);

    @Query("select c from Customer c where c.businessId = :b and c.balance > 0 and (lower(c.name) like :q or lower(c.phone) like :q) order by c.balance desc")
    Page<Customer> searchOwing(@Param("b") UUID b, @Param("q") String q, Pageable pageable);

    @Query("select c from Customer c where c.businessId = :b and c.balance > 0 order by c.balance desc")
    java.util.List<Customer> owing(@Param("b") UUID b);

    @Query("select sum(c.balance) from Customer c where c.businessId = :b and c.balance > 0")
    BigDecimal outstanding(@Param("b") UUID b);
}
