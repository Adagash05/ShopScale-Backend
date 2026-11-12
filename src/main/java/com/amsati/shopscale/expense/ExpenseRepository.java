package com.amsati.shopscale.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {
    long countByBusinessId(java.util.UUID businessId);
    interface DayTotal {
        LocalDate getDay();
        BigDecimal getTotal();
    }

    @Query("select e.expenseDate as day, sum(e.amount) as total from Expense e where e.businessId = :b and e.status = 'APPROVED' "
        + "and e.expenseDate >= :from and e.expenseDate <= :to group by e.expenseDate order by e.expenseDate")
    java.util.List<DayTotal> daily(@Param("b") UUID b, @Param("from") LocalDate from, @Param("to") LocalDate to);

    Optional<Expense> findByIdAndBusinessId(UUID id, UUID businessId);
    Optional<Expense> findByBusinessIdAndClientRequestId(UUID businessId, String clientRequestId);

    @Query("select e from Expense e where e.businessId = :b and e.expenseDate >= :from and e.expenseDate <= :to "
        + "order by e.expenseDate desc, e.createdAt desc")
    Page<Expense> search(@Param("b") UUID b, @Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

    @Query("select sum(e.amount) from Expense e where e.businessId = :b and e.status = 'APPROVED' and e.expenseDate >= :from and e.expenseDate <= :to")
    BigDecimal sumBetween(@Param("b") UUID b, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
