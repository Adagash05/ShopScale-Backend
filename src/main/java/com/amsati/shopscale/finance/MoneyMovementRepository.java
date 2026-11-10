package com.amsati.shopscale.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MoneyMovementRepository extends JpaRepository<MoneyMovement, UUID> {
    @Query("select coalesce(sum(case when m.direction = 'IN' then m.amount else -m.amount end), 0) from MoneyMovement m where m.businessId = :b and m.accountId = :a")
    BigDecimal balanceOf(@Param("b") UUID businessId, @Param("a") UUID accountId);

    interface KindTotal { String getKind(); String getDirection(); BigDecimal getTotal(); }

    @Query("select m.kind as kind, m.direction as direction, sum(m.amount) as total from MoneyMovement m "
        + "where m.businessId = :b and m.createdAt >= :from and m.createdAt < :to group by m.kind, m.direction")
    List<KindTotal> totalsBetween(@Param("b") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select m from MoneyMovement m where m.businessId = :b and m.accountId = :a order by m.createdAt desc")
    Page<MoneyMovement> forAccount(@Param("b") UUID businessId, @Param("a") UUID accountId, Pageable pageable);

    /** Net cash a person handled inside the cash account(s) between two moments. */
    @Query("select coalesce(sum(case when m.direction = 'IN' then m.amount else -m.amount end), 0) from MoneyMovement m, MoneyAccount a "
        + "where m.accountId = a.id and a.kind = 'CASH' and m.businessId = :b and m.createdBy = :u and m.createdAt >= :from and m.createdAt <= :to "
        + "and (m.branchId = :branch or m.branchId is null) and m.kind not in ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT')")
    BigDecimal netCashBy(@Param("b") UUID businessId, @Param("u") UUID userId, @Param("from") Instant from, @Param("to") Instant to, @Param("branch") UUID branchId);
}
