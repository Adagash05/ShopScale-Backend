package com.amsati.shopscale.finance;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.DateRange;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.common.PaymentMethod;
import com.amsati.shopscale.customer.Customer;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.purchasing.Supplier;
import com.amsati.shopscale.purchasing.SupplierRepository;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The money ledger. Every receipt or payment made by the business is recorded as a movement on a cash or bank account,
 * in the same transaction as the sale, expense or payment that caused it. Balances are opening balance + movements.
 */
@Service
@RequiredArgsConstructor
public class FinanceService {
    private final MoneyAccountRepository accounts;
    private final MoneyMovementRepository movements;
    private final BusinessRepository businesses;
    private final CustomerRepository customers;
    private final SupplierRepository suppliers;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    // ---------- posting (called by other services inside their own transaction) ----------
    public MoneyMovement record(UUID businessId, PaymentMethod method, boolean moneyIn, BigDecimal amount, String kind,
                                String refType, UUID refId, String note, UUID userId, UUID branchId) {
        if (amount == null || amount.signum() <= 0) return null;
        MoneyAccount acc = accountFor(businessId, method);
        MoneyMovement m = new MoneyMovement();
        m.setBusinessId(businessId);
        m.setAccountId(acc.getId());
        m.setBranchId(branchId);
        m.setDirection(moneyIn ? "IN" : "OUT");
        m.setAmount(Money.scale(amount));
        m.setMethod(method == null ? null : method.name());
        m.setKind(kind);
        m.setRefType(refType);
        m.setRefId(refId);
        m.setNote(note);
        m.setCreatedBy(userId);
        return movements.save(m);
    }

    /** Cash goes to the cash account; transfer and card to the bank account. Created on demand if missing. */
    public MoneyAccount accountFor(UUID businessId, PaymentMethod method) {
        String kind = method == PaymentMethod.CASH || method == null ? "CASH" : "BANK";
        return accounts.findFirstByBusinessIdAndKindAndActiveTrueOrderByCreatedAtAsc(businessId, kind).orElseGet(() -> {
            MoneyAccount a = new MoneyAccount();
            a.setBusinessId(businessId);
            a.setName(kind.equals("CASH") ? "Cash on hand" : "Bank account");
            a.setKind(kind);
            return accounts.save(a);
        });
    }

    // ---------- accounts ----------
    public record AccountRequest(@NotBlank @Size(max = 100) String name, @NotNull String kind,
                                 @DecimalMin(value = "0", message = "Opening balance cannot be negative") BigDecimal openingBalance) {}
    public record AccountDto(UUID id, String name, String kind, BigDecimal openingBalance, BigDecimal balance, boolean active) {}
    public record ManualRequest(@NotNull UUID accountId, @NotNull String type,
                                @NotNull @DecimalMin(value = "0.01", message = "Enter an amount above zero") BigDecimal amount,
                                @Size(max = 300) String note) {}
    public record TransferRequest(@NotNull UUID fromAccountId, @NotNull UUID toAccountId,
                                  @NotNull @DecimalMin(value = "0.01", message = "Enter an amount above zero") BigDecimal amount,
                                  @Size(max = 300) String note) {}
    public record MovementDto(UUID id, String direction, BigDecimal amount, String method, String kind, String note, Instant createdAt) {}

    private static final Set<String> KINDS = Set.of("CASH", "BANK", "MOBILE_MONEY");

    @Transactional(readOnly = true)
    public List<AccountDto> listAccounts() {
        UUID bid = Tenant.businessId();
        List<AccountDto> out = new ArrayList<>();
        for (MoneyAccount a : accounts.findByBusinessIdOrderByCreatedAtAsc(bid)) out.add(dto(a));
        return out;
    }

    private AccountDto dto(MoneyAccount a) {
        BigDecimal bal = a.getOpeningBalance().add(movements.balanceOf(a.getBusinessId(), a.getId()));
        return new AccountDto(a.getId(), a.getName(), a.getKind(), a.getOpeningBalance(), Money.scale(bal), a.isActive());
    }

    @Transactional
    public AccountDto createAccount(AccountRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireWritable(bid);
        subscriptions.requireFeature(bid, "CASH_ACCOUNTS", "Cash and bank accounts");
        if (!KINDS.contains(r.kind())) throw ApiException.badRequest("Choose cash, bank or mobile money");
        if (accounts.existsByBusinessIdAndNameIgnoreCase(bid, r.name().trim())) throw ApiException.conflict("You already have an account with that name");
        MoneyAccount a = new MoneyAccount();
        a.setBusinessId(bid);
        a.setName(r.name().trim());
        a.setKind(r.kind());
        a.setOpeningBalance(r.openingBalance() == null ? BigDecimal.ZERO : Money.scale(r.openingBalance()));
        accounts.save(a);
        audit.record(bid, Tenant.userId(), "ACCOUNT_CREATED", "money_account", a.getId(), a.getName());
        return dto(a);
    }

    @Transactional
    public AccountDto renameAccount(UUID id, String name, boolean active) {
        UUID bid = Tenant.businessId();
        MoneyAccount a = accounts.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Account not found"));
        if (name == null || name.isBlank()) throw ApiException.badRequest("Enter a name");
        a.setName(name.trim());
        if (!active && a.isActive() && !"MOBILE_MONEY".equals(a.getKind())) {
            long sameKind = accounts.findByBusinessIdOrderByCreatedAtAsc(bid).stream().filter(x -> x.isActive() && x.getKind().equals(a.getKind())).count();
            if (sameKind <= 1) throw ApiException.badRequest("Keep at least one " + a.getKind().toLowerCase() + " account so payments have somewhere to go");
        }
        a.setActive(active);
        audit.record(bid, Tenant.userId(), "ACCOUNT_UPDATED", "money_account", id, a.getName());
        return dto(a);
    }

    /** Money put in or taken out by hand (owner top-up, bank deposit, withdrawal). */
    @Transactional
    public AccountDto manual(ManualRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireWritable(bid);
        MoneyAccount a = accounts.findByIdAndBusinessId(r.accountId(), bid).orElseThrow(() -> ApiException.notFound("Account not found"));
        boolean in = switch (r.type()) { case "DEPOSIT" -> true; case "WITHDRAWAL" -> false; default -> throw ApiException.badRequest("Choose deposit or withdrawal"); };
        BigDecimal amount = Money.scale(r.amount());
        if (!in && a.getOpeningBalance().add(movements.balanceOf(bid, a.getId())).compareTo(amount) < 0) {
            throw ApiException.badRequest("There is not enough money in " + a.getName());
        }
        MoneyMovement m = new MoneyMovement();
        m.setBusinessId(bid); m.setAccountId(a.getId()); m.setDirection(in ? "IN" : "OUT"); m.setAmount(amount);
        m.setKind(r.type()); m.setNote(r.note()); m.setCreatedBy(Tenant.userId());
        movements.save(m);
        audit.record(bid, Tenant.userId(), "ACCOUNT_" + r.type(), "money_account", a.getId(), amount.toPlainString());
        return dto(a);
    }

    @Transactional
    public List<AccountDto> transfer(TransferRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireWritable(bid);
        if (r.fromAccountId().equals(r.toAccountId())) throw ApiException.badRequest("Choose two different accounts");
        MoneyAccount from = accounts.findByIdAndBusinessId(r.fromAccountId(), bid).orElseThrow(() -> ApiException.notFound("Account not found"));
        MoneyAccount to = accounts.findByIdAndBusinessId(r.toAccountId(), bid).orElseThrow(() -> ApiException.notFound("Account not found"));
        BigDecimal amount = Money.scale(r.amount());
        if (from.getOpeningBalance().add(movements.balanceOf(bid, from.getId())).compareTo(amount) < 0) {
            throw ApiException.badRequest("There is not enough money in " + from.getName());
        }
        for (boolean out : new boolean[] {true, false}) {
            MoneyMovement m = new MoneyMovement();
            m.setBusinessId(bid); m.setAccountId(out ? from.getId() : to.getId()); m.setDirection(out ? "OUT" : "IN"); m.setAmount(amount);
            m.setKind(out ? "TRANSFER_OUT" : "TRANSFER_IN"); m.setNote(r.note()); m.setCreatedBy(Tenant.userId());
            movements.save(m);
        }
        audit.record(bid, Tenant.userId(), "ACCOUNT_TRANSFER", "money_account", from.getId(), amount.toPlainString() + " to " + to.getName());
        return List.of(dto(from), dto(to));
    }

    @Transactional(readOnly = true)
    public Page<MovementDto> accountMovements(UUID accountId, int page, int size) {
        UUID bid = Tenant.businessId();
        accounts.findByIdAndBusinessId(accountId, bid).orElseThrow(() -> ApiException.notFound("Account not found"));
        return movements.forAccount(bid, accountId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)))
            .map(m -> new MovementDto(m.getId(), m.getDirection(), m.getAmount(), m.getMethod(), m.getKind(), m.getNote(), m.getCreatedAt()));
    }

    // ---------- summaries ----------
    public record Line(String kind, BigDecimal amount) {}
    public record Summary(LocalDate from, LocalDate to, BigDecimal moneyIn, BigDecimal moneyOut, BigDecimal net, List<Line> inBy, List<Line> outBy,
                          BigDecimal totalOnHand, BigDecimal customersOwe, int customersOwing, BigDecimal youOweSuppliers, int suppliersOwed) {}
    public record Debtor(UUID id, String name, String phone, BigDecimal balance) {}
    public record Payable(UUID id, String name, String phone, BigDecimal balance) {}

    @Transactional(readOnly = true)
    public Summary summary(String range, LocalDate from, LocalDate to) {
        UUID bid = Tenant.businessId();
        Business b = businesses.findById(bid).orElseThrow();
        DateRange r = DateRange.resolve(range, from, to, LocalDate.now(b.zone()));
        Map<String, BigDecimal> in = new TreeMap<>(), out = new TreeMap<>();
        BigDecimal tin = BigDecimal.ZERO, tout = BigDecimal.ZERO;
        for (MoneyMovementRepository.KindTotal t : movements.totalsBetween(bid, r.start(b.zone()), r.endExclusive(b.zone()))) {
            boolean isIn = "IN".equals(t.getDirection());
            // Moving money between your own accounts is not income or spending.
            if (t.getKind().startsWith("TRANSFER_")) continue;
            (isIn ? in : out).merge(t.getKind(), t.getTotal(), BigDecimal::add);
            if (isIn) tin = tin.add(t.getTotal()); else tout = tout.add(t.getTotal());
        }
        BigDecimal onHand = BigDecimal.ZERO;
        for (MoneyAccount a : accounts.findByBusinessIdOrderByCreatedAtAsc(bid)) {
            if (a.isActive()) onHand = onHand.add(a.getOpeningBalance()).add(movements.balanceOf(bid, a.getId()));
        }
        List<Customer> owing = customers.owing(bid);
        BigDecimal cust = customers.outstanding(bid);
        BigDecimal supp = suppliers.totalOwed(bid);
        int suppCount = (int) suppliers.search(bid, "%", PageRequest.of(0, 1000)).stream().filter(s -> s.getBalance().signum() > 0).count();
        return new Summary(r.from(), r.to(), Money.scale(tin), Money.scale(tout), Money.scale(tin.subtract(tout)),
            lines(in), lines(out), Money.scale(onHand), Money.scale(cust == null ? BigDecimal.ZERO : cust), owing.size(),
            Money.scale(supp == null ? BigDecimal.ZERO : supp), suppCount);
    }

    private static List<Line> lines(Map<String, BigDecimal> m) {
        return m.entrySet().stream().map(e -> new Line(e.getKey(), Money.scale(e.getValue()))).toList();
    }

    @Transactional(readOnly = true)
    public List<Debtor> debtors() {
        return customers.owing(Tenant.businessId()).stream().map(c -> new Debtor(c.getId(), c.getName(), c.getPhone(), Money.scale(c.getBalance()))).toList();
    }

    @Transactional(readOnly = true)
    public List<Payable> payables() {
        return suppliers.search(Tenant.businessId(), "%", PageRequest.of(0, 500)).stream().filter(s -> s.getBalance().signum() > 0)
            .sorted((a, c) -> c.getBalance().compareTo(a.getBalance()))
            .map(s -> new Payable(s.getId(), s.getName(), s.getPhone(), Money.scale(s.getBalance()))).toList();
    }
}
