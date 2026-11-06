package com.amsati.shopscale.expense;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.DateRange;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.common.PaymentMethod;
import com.amsati.shopscale.security.Tenant;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExpenseService {
    private final ExpenseRepository expenses;
    private final BusinessRepository businesses;
    private final AuditService audit;

    public record ExpenseRequest(@NotBlank @Size(max = 80) String category,
                                 @NotNull @DecimalMin(value = "0.01", message = "Enter an amount above zero") BigDecimal amount,
                                 LocalDate expenseDate,
                                 @Size(max = 300) String description,
                                 @NotNull PaymentMethod paymentMethod) {}

    public record ExpenseDto(UUID id, String category, BigDecimal amount, LocalDate expenseDate, String description,
                             PaymentMethod paymentMethod) {}

    public record ExpensePage(Page<ExpenseDto> page, BigDecimal total) {}

    private static ExpenseDto dto(Expense e) {
        return new ExpenseDto(e.getId(), e.getCategory(), e.getAmount(), e.getExpenseDate(), e.getDescription(), e.getPaymentMethod());
    }

    @Transactional
    public ExpenseDto create(ExpenseRequest r) {
        UUID bid = Tenant.businessId();
        Business b = businesses.findById(bid).orElseThrow();
        Expense e = new Expense();
        e.setBusinessId(bid);
        e.setCategory(r.category().trim());
        e.setAmount(Money.scale(r.amount()));
        e.setExpenseDate(r.expenseDate() == null ? LocalDate.now(b.zone()) : r.expenseDate());
        e.setDescription(r.description());
        e.setPaymentMethod(r.paymentMethod());
        e.setCreatedBy(Tenant.userId());
        expenses.save(e);
        audit.record(bid, Tenant.userId(), "EXPENSE_CREATED", "expense", e.getId(), e.getCategory() + " " + e.getAmount().toPlainString());
        return dto(e);
    }

    @Transactional(readOnly = true)
    public ExpensePage list(String range, LocalDate from, LocalDate to, int page, int size) {
        UUID bid = Tenant.businessId();
        Business b = businesses.findById(bid).orElseThrow();
        DateRange r = DateRange.resolve(range, from, to, LocalDate.now(b.zone()));
        Page<ExpenseDto> p = expenses.search(bid, r.from(), r.to(),
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))).map(ExpenseService::dto);
        return new ExpensePage(p, Money.scale(expenses.sumBetween(bid, r.from(), r.to())));
    }

    @Transactional
    public void delete(UUID id) {
        UUID bid = Tenant.businessId();
        Expense e = expenses.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Expense not found"));
        expenses.delete(e);
        audit.record(bid, Tenant.userId(), "EXPENSE_DELETED", "expense", id, e.getCategory() + " " + e.getAmount().toPlainString());
    }
}
