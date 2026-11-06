package com.amsati.shopscale.sales;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.Product;
import com.amsati.shopscale.catalog.ProductRepository;
import com.amsati.shopscale.catalog.ProductStatus;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.DateRange;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.common.PaymentMethod;
import com.amsati.shopscale.customer.Customer;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.inventory.MovementType;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SaleService {
    private final SaleRepository sales;
    private final SaleItemRepository saleItems;
    private final SalePaymentRepository salePayments;
    private final BusinessRepository businesses;
    private final ProductVariantRepository variants;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final StockService stock;
    private final SubscriptionService subscriptions;
    private final AuditService audit;
    private final AccessService access;

    // ---------- request / response ----------
    public record ItemRequest(@NotNull UUID variantId, @Min(value = 1, message = "Quantity must be at least 1") int quantity) {}

    public record PayRequest(@NotNull PaymentMethod method,
                             @NotNull @DecimalMin(value = "0.01", message = "Enter an amount above zero") BigDecimal amount) {}

    public record SaleRequest(@NotEmpty(message = "Add at least one item") @Valid List<ItemRequest> items,
                              @DecimalMin(value = "0", message = "Discount cannot be negative") BigDecimal discount,
                              UUID customerId,
                              @Valid List<PayRequest> payments,
                              @Size(max = 300) String note,
                              @Size(max = 64) String clientRequestId) {}

    public record ItemDto(UUID variantId, String productName, String variantLabel, int quantity,
                          BigDecimal unitPrice, BigDecimal lineTotal) {}

    public record PaymentDto(PaymentMethod method, BigDecimal amount) {}

    public record SaleDto(UUID id, long saleNumber, SaleStatus status, UUID customerId, String customerName,
                          BigDecimal subtotal, BigDecimal discount, BigDecimal total, BigDecimal amountPaid,
                          BigDecimal amountOwed, String note, Instant createdAt, Instant refundedAt,
                          List<ItemDto> items, List<PaymentDto> payments) {}

    // ---------- create ----------
    /**
     * Records a sale atomically: sale, items, payments, stock, stock movements and customer balance commit
     * together or not at all. Prices and costs always come from the database, never from the client.
     */
    @Transactional
    public SaleDto create(SaleRequest req) {
        UUID bid = Tenant.businessId();
        UUID userId = Tenant.userId();

        // Idempotency: a repeated tap or retried request returns the original sale.
        String requestId = req.clientRequestId() == null || req.clientRequestId().isBlank() ? null : req.clientRequestId();
        if (requestId != null) {
            Optional<Sale> prior = sales.findByBusinessIdAndClientRequestId(bid, requestId);
            if (prior.isPresent()) return toDto(prior.get());
        }

        // Serialise sales per business: locks the business row, also gives us a gap-free sale number.
        Business business = businesses.findByIdForUpdate(bid).orElseThrow(() -> ApiException.notFound("Business not found"));

        // Merge duplicate lines for the same variant.
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        for (ItemRequest i : req.items()) wanted.merge(i.variantId(), i.quantity(), Integer::sum);

        List<ProductVariant> locked = variants.lockAll(bid, wanted.keySet());
        if (locked.size() != wanted.size()) throw ApiException.badRequest("One of the items is no longer available");
        Map<UUID, ProductVariant> byId = locked.stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        Map<UUID, Product> productById = products.findAllById(
            locked.stream().map(v -> v.getProduct().getId()).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(Product::getId, p -> p));

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal costTotal = BigDecimal.ZERO;
        for (Map.Entry<UUID, Integer> e : wanted.entrySet()) {
            ProductVariant v = byId.get(e.getKey());
            Product p = productById.get(v.getProduct().getId());
            String display = displayName(p, v);
            if (!v.isActive() || p.getStatus() != ProductStatus.ACTIVE) throw ApiException.badRequest(display + " is no longer for sale");
            if (!business.isAllowNegativeStock() && v.getQuantity() < e.getValue()) {
                throw ApiException.conflict("Not enough stock for " + display + ". Only " + v.getQuantity() + " left.");
            }
            subtotal = subtotal.add(v.getPrice().multiply(BigDecimal.valueOf(e.getValue())));
            costTotal = costTotal.add(v.getCost().multiply(BigDecimal.valueOf(e.getValue())));
        }
        subtotal = Money.scale(subtotal);
        costTotal = Money.scale(costTotal);

        BigDecimal discount = Money.scale(req.discount());
        if (discount.signum() > 0) access.require(Permission.SALES_DISCOUNT);
        if (discount.compareTo(subtotal) > 0) throw ApiException.badRequest("The discount is more than the total of the items");
        BigDecimal total = subtotal.subtract(discount);

        List<PayRequest> pays = req.payments() == null ? List.of() : req.payments();
        BigDecimal paid = Money.scale(pays.stream().map(PayRequest::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (paid.compareTo(total) > 0) throw ApiException.badRequest("Payments add up to more than the sale total");
        BigDecimal owed = total.subtract(paid);

        Customer customer = null;
        if (req.customerId() != null || owed.signum() > 0) {
            if (req.customerId() == null) throw ApiException.badRequest("Choose a customer to sell on credit");
            customer = customers.findForUpdate(req.customerId(), bid).orElseThrow(() -> ApiException.badRequest("Customer not found"));
        }
        if (owed.signum() > 0) subscriptions.requireFeature(bid, "CUSTOMER_CREDIT", "Selling on credit");
        if (total.signum() == 0 && paid.signum() == 0 && owed.signum() == 0 && subtotal.signum() > 0 && discount.compareTo(subtotal) != 0) {
            throw ApiException.badRequest("Nothing to record");
        }

        business.setSaleCounter(business.getSaleCounter() + 1);

        Sale sale = new Sale();
        sale.setBusinessId(bid);
        sale.setSaleNumber(business.getSaleCounter());
        sale.setCustomerId(customer == null ? null : customer.getId());
        sale.setStatus(SaleStatus.COMPLETED);
        sale.setSubtotal(subtotal);
        sale.setDiscount(discount);
        sale.setTotal(total);
        sale.setAmountPaid(paid);
        sale.setAmountOwed(owed);
        sale.setCostTotal(costTotal);
        sale.setNote(req.note());
        sale.setClientRequestId(requestId);
        sale.setCreatedBy(userId);
        sales.save(sale);

        String reference = "SALE-" + sale.getSaleNumber();
        for (Map.Entry<UUID, Integer> e : wanted.entrySet()) {
            ProductVariant v = byId.get(e.getKey());
            Product p = productById.get(v.getProduct().getId());
            SaleItem item = new SaleItem();
            item.setSale(sale);
            item.setBusinessId(bid);
            item.setVariantId(v.getId());
            item.setProductName(p.getName());
            item.setVariantLabel(v.getLabel());
            item.setQuantity(e.getValue());
            item.setUnitPrice(v.getPrice());
            item.setUnitCost(v.getCost());
            item.setLineTotal(Money.scale(v.getPrice().multiply(BigDecimal.valueOf(e.getValue()))));
            saleItems.save(item);
            stock.apply(v, MovementType.SALE, -e.getValue(), null, reference, userId, business.isAllowNegativeStock());
        }
        for (PayRequest pr : pays) {
            SalePayment sp = new SalePayment();
            sp.setSale(sale);
            sp.setBusinessId(bid);
            sp.setMethod(pr.method());
            sp.setAmount(Money.scale(pr.amount()));
            salePayments.save(sp);
        }
        if (customer != null && owed.signum() > 0) {
            customer.setBalance(customer.getBalance().add(owed));
            customer.setUpdatedAt(Instant.now());
        }
        audit.record(bid, userId, "SALE_CREATED", "sale", sale.getId(), reference + " total " + total.toPlainString());
        return toDto(sale);
    }

    // ---------- refund ----------
    /** Full refund: returns stock, removes the sale from sales/profit figures, and cancels any unpaid balance. */
    @Transactional
    public SaleDto refund(UUID saleId) {
        UUID bid = Tenant.businessId();
        UUID userId = Tenant.userId();
        Business business = businesses.findByIdForUpdate(bid).orElseThrow(() -> ApiException.notFound("Business not found"));
        Sale sale = sales.findForUpdate(saleId, bid).orElseThrow(() -> ApiException.notFound("Sale not found"));
        if (sale.getStatus() == SaleStatus.REFUNDED) throw ApiException.conflict("This sale has already been refunded");

        List<SaleItem> items = saleItems.findBySaleId(saleId);
        Map<UUID, ProductVariant> byId = variants.lockAll(bid, items.stream().map(SaleItem::getVariantId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        String reference = "REFUND-" + sale.getSaleNumber();
        for (SaleItem i : items) {
            ProductVariant v = byId.get(i.getVariantId());
            if (v != null) stock.apply(v, MovementType.REFUND, i.getQuantity(), "Sale refunded", reference, userId, true);
        }
        if (sale.getCustomerId() != null && sale.getAmountOwed().signum() > 0) {
            customers.findForUpdate(sale.getCustomerId(), bid).ifPresent(c -> {
                c.setBalance(c.getBalance().subtract(sale.getAmountOwed()));
                c.setUpdatedAt(Instant.now());
            });
        }
        sale.setStatus(SaleStatus.REFUNDED);
        sale.setRefundedAt(Instant.now());
        audit.record(bid, userId, "SALE_REFUNDED", "sale", saleId, reference);
        return toDto(sale);
    }

    // ---------- reads ----------
    @Transactional(readOnly = true)
    public SaleDto get(UUID id) {
        Sale s = sales.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Sale not found"));
        // Staff who may only see their own sales get "not found" for anyone else's.
        if (!access.current().contains(Permission.SALES_VIEW) && !Tenant.userId().equals(s.getCreatedBy())) {
            throw ApiException.notFound("Sale not found");
        }
        return toDto(s);
    }

    @Transactional(readOnly = true)
    public Page<SaleSummary> list(String range, LocalDate from, LocalDate to, int page, int size) {
        UUID bid = Tenant.businessId();
        Business b = businesses.findById(bid).orElseThrow();
        DateRange r = DateRange.resolve(range, from, to, LocalDate.now(b.zone()));
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        if (!access.current().contains(Permission.SALES_VIEW)) {
            return sales.searchMine(bid, Tenant.userId(), r.start(b.zone()), r.endExclusive(b.zone()), pr);
        }
        return sales.search(bid, r.start(b.zone()), r.endExclusive(b.zone()), pr);
    }

    private SaleDto toDto(Sale s) {
        String customerName = s.getCustomerId() == null ? null
            : customers.findByIdAndBusinessId(s.getCustomerId(), s.getBusinessId()).map(Customer::getName).orElse(null);
        List<ItemDto> items = saleItems.findBySaleId(s.getId()).stream()
            .map(i -> new ItemDto(i.getVariantId(), i.getProductName(), i.getVariantLabel(), i.getQuantity(), i.getUnitPrice(), i.getLineTotal())).toList();
        List<PaymentDto> pays = salePayments.findBySaleId(s.getId()).stream()
            .map(p -> new PaymentDto(p.getMethod(), p.getAmount())).toList();
        return new SaleDto(s.getId(), s.getSaleNumber(), s.getStatus(), s.getCustomerId(), customerName, s.getSubtotal(),
            s.getDiscount(), s.getTotal(), s.getAmountPaid(), s.getAmountOwed(), s.getNote(), s.getCreatedAt(),
            s.getRefundedAt(), items, pays);
    }

    private static String displayName(Product p, ProductVariant v) {
        return v.getLabel() == null ? p.getName() : p.getName() + " (" + v.getLabel() + ")";
    }
}
