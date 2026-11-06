package com.amsati.shopscale.customer;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.common.PaymentMethod;
import com.amsati.shopscale.sales.SaleRepository;
import com.amsati.shopscale.sales.SaleSummary;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerService {
    private final CustomerRepository customers;
    private final CustomerPaymentRepository payments;
    private final SaleRepository sales;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    public record CustomerRequest(@NotBlank @Size(max = 150) String name, @Size(max = 40) String phone,
                                  @Size(max = 255) String email, @Size(max = 500) String address,
                                  @Size(max = 1000) String notes) {}

    public record PaymentRequest(@NotNull @DecimalMin(value = "0.01", message = "Enter an amount above zero") BigDecimal amount,
                                 @NotNull PaymentMethod method, @Size(max = 300) String note) {}

    public record CustomerDto(UUID id, String name, String phone, String email, String address, String notes, BigDecimal balance) {}

    public record PaymentDto(UUID id, BigDecimal amount, PaymentMethod method, String note, Instant createdAt) {}

    public record CustomerDetail(CustomerDto customer, List<PaymentDto> payments, List<SaleSummary> recentSales) {}

    private static CustomerDto dto(Customer c) {
        return new CustomerDto(c.getId(), c.getName(), c.getPhone(), c.getEmail(), c.getAddress(), c.getNotes(), Money.scale(c.getBalance()));
    }

    private static String like(String q) {
        return "%" + (q == null ? "" : q.trim().toLowerCase().replace("%", "").replace("_", "")) + "%";
    }

    @Transactional(readOnly = true)
    public Page<CustomerDto> list(String q, boolean owingOnly, int page, int size) {
        UUID bid = Tenant.businessId();
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<Customer> found = owingOnly ? customers.searchOwing(bid, like(q), pr) : customers.search(bid, like(q), pr);
        return found.map(CustomerService::dto);
    }

    @Transactional
    public CustomerDto create(CustomerRequest r) {
        Customer c = new Customer();
        c.setBusinessId(Tenant.businessId());
        apply(c, r);
        customers.save(c);
        audit.record(c.getBusinessId(), Tenant.userId(), "CUSTOMER_CREATED", "customer", c.getId(), c.getName());
        return dto(c);
    }

    @Transactional
    public CustomerDto update(UUID id, CustomerRequest r) {
        Customer c = customers.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Customer not found"));
        apply(c, r);
        c.setUpdatedAt(Instant.now());
        return dto(c);
    }

    private void apply(Customer c, CustomerRequest r) {
        c.setName(r.name().trim());
        c.setPhone(blank(r.phone()));
        c.setEmail(blank(r.email()));
        c.setAddress(blank(r.address()));
        c.setNotes(blank(r.notes()));
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    @Transactional(readOnly = true)
    public CustomerDetail detail(UUID id) {
        UUID bid = Tenant.businessId();
        Customer c = customers.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Customer not found"));
        List<PaymentDto> pays = payments.findTop50ByBusinessIdAndCustomerIdOrderByCreatedAtDesc(bid, id).stream()
            .map(p -> new PaymentDto(p.getId(), p.getAmount(), p.getMethod(), p.getNote(), p.getCreatedAt())).toList();
        List<SaleSummary> recent = sales.forCustomer(bid, id, PageRequest.of(0, 20)).getContent();
        return new CustomerDetail(dto(c), pays, recent);
    }

    /** Records money a customer pays towards what they owe. */
    @Transactional
    public CustomerDetail recordPayment(UUID id, PaymentRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireFeature(bid, "CUSTOMER_CREDIT", "Customer credit");
        Customer c = customers.findForUpdate(id, bid).orElseThrow(() -> ApiException.notFound("Customer not found"));
        BigDecimal amount = Money.scale(r.amount());
        if (amount.compareTo(c.getBalance()) > 0) {
            throw ApiException.badRequest("That is more than this customer owes (" + Money.scale(c.getBalance()).toPlainString() + ")");
        }
        c.setBalance(c.getBalance().subtract(amount));
        c.setUpdatedAt(Instant.now());
        CustomerPayment p = new CustomerPayment();
        p.setBusinessId(bid);
        p.setCustomerId(id);
        p.setAmount(amount);
        p.setMethod(r.method());
        p.setNote(r.note());
        p.setCreatedBy(Tenant.userId());
        payments.save(p);
        audit.record(bid, Tenant.userId(), "CUSTOMER_PAYMENT", "customer", id, amount.toPlainString());
        return detail(id);
    }
}
