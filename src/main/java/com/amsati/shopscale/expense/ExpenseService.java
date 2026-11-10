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
import java.time.Instant;
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
    private final com.amsati.shopscale.finance.FinanceService finance;
    private final com.amsati.shopscale.security.AccessService access;
    private final com.amsati.shopscale.subscription.SubscriptionService subscriptions;
    private final com.amsati.shopscale.notification.NotificationService notify;

    public record ExpenseRequest(@NotBlank @Size(max = 80) String category,
                                 @NotNull @DecimalMin(value = "0.01", message = "Enter an amount above zero") BigDecimal amount,
                                 LocalDate expenseDate,
                                 @Size(max = 300) String description,
                                 @NotNull PaymentMethod paymentMethod) {}

    public record ExpenseDto(UUID id, String category, BigDecimal amount, LocalDate expenseDate, String description,
                             PaymentMethod paymentMethod, String status, String decisionNote) {}

    public record DecisionRequest(@Size(max = 300) String note) {}

    public record ExpensePage(Page<ExpenseDto> page, BigDecimal total) {}

    private static ExpenseDto dto(Expense e) {
        return new ExpenseDto(e.getId(), e.getCategory(), e.getAmount(), e.getExpenseDate(), e.getDescription(), e.getPaymentMethod(), e.getStatus(), e.getDecisionNote());
    }

    @Transactional
    public ExpenseDto create(ExpenseRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireExpenseCapacity(bid, expenses.countByBusinessId(bid) + 1);
        Business b = businesses.findById(bid).orElseThrow();
        Expense e = new Expense();
        e.setBusinessId(bid);
        e.setCategory(r.category().trim());
        e.setAmount(Money.scale(r.amount()));
        e.setExpenseDate(r.expenseDate() == null ? LocalDate.now(b.zone()) : r.expenseDate());
        e.setDescription(r.description());
        e.setPaymentMethod(r.paymentMethod());
        e.setCreatedBy(Tenant.userId());
        // Larger expenses from people who cannot approve them wait for approval and do not touch the books until approved.
        BigDecimal limit = b.getExpenseApprovalThreshold();
        boolean needsApproval = limit != null && e.getAmount().compareTo(limit) >= 0 && !access.current().contains(com.amsati.shopscale.security.Permission.EXPENSES_APPROVE);
        e.setStatus(needsApproval ? "PENDING" : "APPROVED");
        if (!needsApproval) { e.setDecidedBy(Tenant.userId()); e.setDecidedAt(Instant.now()); }
        expenses.save(e);
        if (!needsApproval) postToLedger(e);
        else notify.publish(bid, com.amsati.shopscale.security.Permission.EXPENSES_APPROVE, "EXPENSE_PENDING", "Expense waiting for approval",
            e.getCategory() + " · ₦" + e.getAmount().toPlainString(), "/expenses", null);
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
        if ("APPROVED".equals(e.getStatus())) {
            finance.record(bid, e.getPaymentMethod(), true, e.getAmount(), "EXPENSE_REVERSAL", "EXPENSE", e.getId(), "Expense deleted: " + e.getCategory(), Tenant.userId(), e.getBranchId());
        }
        expenses.delete(e);
        audit.record(bid, Tenant.userId(), "EXPENSE_DELETED", "expense", id, e.getCategory() + " " + e.getAmount().toPlainString());
    }

    private void postToLedger(Expense e) {
        finance.record(e.getBusinessId(), e.getPaymentMethod(), false, e.getAmount(), "EXPENSE", "EXPENSE", e.getId(), e.getCategory(), e.getCreatedBy(), e.getBranchId());
    }

    @Transactional
    public ExpenseDto decide(UUID id, boolean approve, String note) {
        UUID bid = Tenant.businessId();
        Expense e = expenses.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Expense not found"));
        if (!"PENDING".equals(e.getStatus())) throw ApiException.conflict("This expense has already been decided");
        e.setStatus(approve ? "APPROVED" : "REJECTED");
        e.setDecidedBy(Tenant.userId());
        e.setDecidedAt(Instant.now());
        e.setDecisionNote(note == null || note.isBlank() ? null : note.trim());
        if (approve) postToLedger(e);
        audit.record(bid, Tenant.userId(), approve ? "EXPENSE_APPROVED" : "EXPENSE_REJECTED", "expense", id, e.getCategory() + " " + e.getAmount().toPlainString());
        return dto(e);
    }

    @Transactional
    public BigDecimal setApprovalLimit(BigDecimal limit) {
        UUID bid = Tenant.businessId();
        Business b = businesses.findById(bid).orElseThrow();
        b.setExpenseApprovalThreshold(limit == null || limit.signum() <= 0 ? null : Money.scale(limit));
        audit.record(bid, Tenant.userId(), "EXPENSE_APPROVAL_LIMIT", "business", bid, b.getExpenseApprovalThreshold() == null ? "off" : b.getExpenseApprovalThreshold().toPlainString());
        return b.getExpenseApprovalThreshold();
    }

    @Transactional(readOnly = true)
    public BigDecimal approvalLimit() {
        return businesses.findById(Tenant.businessId()).orElseThrow().getExpenseApprovalThreshold();
    }
}
