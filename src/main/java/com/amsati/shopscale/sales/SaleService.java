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
import com.amsati.shopscale.pricing.PricingService;
import com.amsati.shopscale.recipe.Recipe;
import com.amsati.shopscale.recipe.RecipeService;
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
    private final com.amsati.shopscale.finance.FinanceService finance;
    private final com.amsati.shopscale.unit.UnitService units;
    private final PricingService pricingService;
    private final RecipeService recipeService;

    // ---------- request / response ----------
    public record ItemRequest(@NotNull UUID variantId,
                              @NotNull @DecimalMin(value = "0.001", message = "Quantity must be greater than zero") BigDecimal quantity,
                              @Size(max = 20) String unit) {}

    public record PayRequest(@NotNull PaymentMethod method,
                             @NotNull @DecimalMin(value = "0.01", message = "Enter an amount above zero") BigDecimal amount) {}

    public record SaleRequest(@NotEmpty(message = "Add at least one item") @Valid List<ItemRequest> items,
                              @DecimalMin(value = "0", message = "Discount cannot be negative") BigDecimal discount,
                              UUID customerId,
                              @Valid List<PayRequest> payments,
                              @Size(max = 300) String note,
                              @Size(max = 64) String clientRequestId,
                              UUID branchId) {}

    public record ItemDto(UUID id, UUID variantId, String productName, String variantLabel, BigDecimal quantity,
                          String unit, BigDecimal unitPrice, BigDecimal lineTotal, BigDecimal quantityRefunded) {}

    public record PaymentDto(PaymentMethod method, BigDecimal amount) {}

    public record SaleDto(UUID id, long saleNumber, SaleStatus status, UUID customerId, String customerName,
                          BigDecimal subtotal, BigDecimal discount, BigDecimal total, BigDecimal amountPaid,
                          BigDecimal amountOwed, String note, Instant createdAt, Instant refundedAt,
                          List<ItemDto> items, List<PaymentDto> payments, UUID branchId, BigDecimal refundedAmount, String channel, String voidReason) {}

    // ---------- create ----------
    /**
     * Records a sale atomically: sale, items, payments, stock, stock movements and customer balance commit
     * together or not at all. Prices and costs always come from the database, never from the client.
     */
    @Transactional
    public SaleDto create(SaleRequest req) {
        return createInternal(req, "POS", null, null, false);
    }

    public record PreviewRequest(@jakarta.validation.constraints.NotEmpty @Valid List<ItemRequest> items, UUID customerId) {}
    public record PreviewLine(UUID variantId, BigDecimal unitPrice) {}

    /** What each line would cost this customer. Display only; the sale itself is always priced again on the server. */
    @Transactional(readOnly = true)
    public List<PreviewLine> previewPrices(PreviewRequest req) {
        UUID bid = Tenant.businessId();
        Customer customer = req.customerId() == null ? null : customers.findByIdAndBusinessId(req.customerId(), bid).orElse(null);
        Map<UUID, BigDecimal> wanted = new LinkedHashMap<>();
        Map<UUID, ProductVariant> byId = variants.findAllById(req.items().stream().map(ItemRequest::variantId).collect(Collectors.toSet()))
            .stream().filter(v -> v.getBusinessId().equals(bid)).collect(Collectors.toMap(ProductVariant::getId, v -> v));
        for (ItemRequest i : req.items()) {
            ProductVariant v = byId.get(i.variantId());
            if (v == null) continue;
            wanted.merge(v.getId(), units.toBase(v, i.unit(), i.quantity()), BigDecimal::add);
        }
        PricingService.Context ctx = pricingService.context(bid, customer, wanted.keySet());
        return wanted.entrySet().stream().map(e -> new PreviewLine(e.getKey(), Money.scale(ctx.priceFor(byId.get(e.getKey()), e.getValue())))).toList();
    }

    /** Shared by the POS and by quote/order conversion. {@code prepaid} is money already received (a deposit) and is not posted to the ledger again. */
    @Transactional
    public SaleDto createInternal(SaleRequest req, String channel, BigDecimal prepaid, PaymentMethod prepaidMethod, boolean trustedDiscount) {
        return createInternal(req, channel, prepaid, prepaidMethod, trustedDiscount, BigDecimal.ZERO);
    }

    /** {@code surcharge} is server-computed extra charge on top of the items (for example paid menu modifiers). */
    @Transactional
    public SaleDto createInternal(SaleRequest req, String channel, BigDecimal prepaid, PaymentMethod prepaidMethod, boolean trustedDiscount, BigDecimal surcharge) {
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
        UUID branchId = stock.resolveBranch(bid, req.branchId());
        Set<UUID> ids = new LinkedHashSet<>();
        for (ItemRequest i : req.items()) ids.add(i.variantId());
        List<ProductVariant> locked = variants.lockAll(bid, ids);
        if (locked.size() != ids.size()) throw ApiException.badRequest("One of the items is no longer available");
        Map<UUID, ProductVariant> byId = locked.stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        // Quantities are converted to each product's base unit; duplicate lines for one variant are merged.
        Map<UUID, BigDecimal> wanted = new LinkedHashMap<>();
        for (ItemRequest i : req.items()) {
            ProductVariant v = byId.get(i.variantId());
            units.requireValidQuantity(v, i.quantity());
            BigDecimal base = units.toBase(v, i.unit(), i.quantity());
            units.requireValidQuantity(v, base);
            wanted.merge(i.variantId(), base, BigDecimal::add);
        }
        Map<UUID, Product> productById = products.findAllById(
            locked.stream().map(v -> v.getProduct().getId()).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(Product::getId, p -> p));

        Customer customer = null;
        if (req.customerId() != null) {
            customer = customers.findForUpdate(req.customerId(), bid).orElseThrow(() -> ApiException.badRequest("Customer not found"));
        }
        // Prices are decided here from the customer's type/tier, agreed prices and bulk breaks, never from the client.
        PricingService.Context pricing = pricingService.context(bid, customer, wanted.keySet());
        // Menu items made to order use their ingredients, not their own stock.
        Map<UUID, Recipe> madeToOrder = recipeService.madeToOrder(bid, wanted.keySet());
        Map<UUID, BigDecimal> unitCosts = new HashMap<>();
        Map<UUID, BigDecimal> unitPrices = new HashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal costTotal = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal> e : wanted.entrySet()) {
            ProductVariant v = byId.get(e.getKey());
            Product p = productById.get(v.getProduct().getId());
            String display = displayName(p, v);
            if (!v.isActive() || p.getStatus() != ProductStatus.ACTIVE) throw ApiException.badRequest(display + " is no longer for sale");
            Recipe made = madeToOrder.get(v.getId());
            if (made != null) {
                recipeService.requireIngredients(made, e.getValue(), branchId, business.isAllowNegativeStock());
                unitCosts.put(v.getId(), recipeService.unitCost(made));
            } else {
                unitCosts.put(v.getId(), v.getCost());
                BigDecimal here = stock.onHand(v, branchId);
                if (!business.isAllowNegativeStock() && here.compareTo(e.getValue()) < 0) {
                    throw ApiException.conflict("Not enough stock for " + display + ". Only " + here.stripTrailingZeros().toPlainString() + " left at this branch.");
                }
            }
            BigDecimal unitPrice = Money.scale(pricing.priceFor(v, e.getValue()));
            unitPrices.put(v.getId(), unitPrice);
            subtotal = subtotal.add(unitPrice.multiply(e.getValue()));
            costTotal = costTotal.add(unitCosts.get(v.getId()).multiply(e.getValue()));
        }
        subtotal = Money.scale(subtotal);
        costTotal = Money.scale(costTotal);

        BigDecimal discount = Money.scale(req.discount());
        if (discount.signum() > 0 && !trustedDiscount) access.require(Permission.SALES_DISCOUNT);
        if (discount.compareTo(subtotal) > 0) throw ApiException.badRequest("The discount is more than the total of the items");
        BigDecimal extra = surcharge == null ? BigDecimal.ZERO : Money.scale(surcharge);
        BigDecimal total = subtotal.subtract(discount).add(extra);

        List<PayRequest> pays = req.payments() == null ? List.of() : req.payments();
        BigDecimal prepaidAmt = prepaid == null ? BigDecimal.ZERO : Money.scale(prepaid);
        BigDecimal paid = Money.scale(pays.stream().map(PayRequest::amount).reduce(BigDecimal.ZERO, BigDecimal::add)).add(prepaidAmt);
        if (paid.compareTo(total) > 0) throw ApiException.badRequest("Payments add up to more than the sale total");
        BigDecimal owed = total.subtract(paid);

        if (owed.signum() > 0 && customer == null) throw ApiException.badRequest("Choose a customer to sell on credit");
        if (owed.signum() > 0) subscriptions.requireFeature(bid, "CUSTOMER_CREDIT", "Selling on credit");
        if (total.signum() == 0 && paid.signum() == 0 && owed.signum() == 0 && subtotal.signum() > 0 && discount.compareTo(subtotal) != 0) {
            throw ApiException.badRequest("Nothing to record");
        }

        business.setSaleCounter(business.getSaleCounter() + 1);

        Sale sale = new Sale();
        sale.setBusinessId(bid);
        sale.setBranchId(branchId);
        sale.setChannel(channel);
        sale.setSaleNumber(business.getSaleCounter());
        sale.setCustomerId(customer == null ? null : customer.getId());
        sale.setStatus(SaleStatus.COMPLETED);
        sale.setSubtotal(subtotal);
        sale.setDiscount(discount);
        sale.setTotal(total);
        sale.setSurcharge(extra);
        sale.setAmountPaid(paid);
        sale.setAmountOwed(owed);
        sale.setCostTotal(costTotal);
        sale.setNote(req.note());
        sale.setClientRequestId(requestId);
        sale.setCreatedBy(userId);
        sales.save(sale);

        String reference = "SALE-" + sale.getSaleNumber();
        for (Map.Entry<UUID, BigDecimal> e : wanted.entrySet()) {
            ProductVariant v = byId.get(e.getKey());
            Product p = productById.get(v.getProduct().getId());
            SaleItem item = new SaleItem();
            item.setSale(sale);
            item.setBusinessId(bid);
            item.setVariantId(v.getId());
            item.setProductName(p.getName());
            item.setVariantLabel(v.getLabel());
            item.setQuantity(e.getValue());
            item.setUnit(v.getUnit());
            item.setUnitPrice(unitPrices.get(v.getId()));
            item.setUnitCost(unitCosts.get(v.getId()));
            item.setLineTotal(Money.scale(unitPrices.get(v.getId()).multiply(e.getValue())));
            saleItems.save(item);
            Recipe made = madeToOrder.get(v.getId());
            if (made != null) recipeService.consume(made, e.getValue(), branchId, MovementType.SALE, reference, userId, business.isAllowNegativeStock());
            else stock.apply(v, branchId, MovementType.SALE, e.getValue().negate(), null, reference, userId, business.isAllowNegativeStock());
        }
        for (PayRequest pr : pays) {
            SalePayment sp = new SalePayment();
            sp.setSale(sale);
            sp.setBusinessId(bid);
            sp.setMethod(pr.method());
            sp.setAmount(Money.scale(pr.amount()));
            salePayments.save(sp);
            finance.record(bid, pr.method(), true, sp.getAmount(), "SALE", "SALE", sale.getId(), "Sale #" + sale.getSaleNumber(), userId, branchId);
        }
        if (prepaidAmt.signum() > 0) {
            SalePayment dp = new SalePayment();
            dp.setSale(sale);
            dp.setBusinessId(bid);
            dp.setMethod(prepaidMethod == null ? PaymentMethod.CASH : prepaidMethod);
            dp.setAmount(prepaidAmt);
            salePayments.save(dp);
        }
        if (customer != null && owed.signum() > 0) {
            customer.setBalance(customer.getBalance().add(owed));
            customer.setUpdatedAt(Instant.now());
        }
        audit.record(bid, userId, "SALE_CREATED", "sale", sale.getId(), reference + " total " + total.toPlainString());
        return toDto(sale);
    }

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

    /** Public view used by other sales services (returns). */
    public SaleDto view(Sale s) { return toDto(s); }

    private SaleDto toDto(Sale s) {
        String customerName = s.getCustomerId() == null ? null
            : customers.findByIdAndBusinessId(s.getCustomerId(), s.getBusinessId()).map(Customer::getName).orElse(null);
        List<ItemDto> items = saleItems.findBySaleId(s.getId()).stream()
            .map(i -> new ItemDto(i.getId(), i.getVariantId(), i.getProductName(), i.getVariantLabel(), i.getQuantity(), i.getUnit(), i.getUnitPrice(), i.getLineTotal(), i.getQuantityRefunded())).toList();
        List<PaymentDto> pays = salePayments.findBySaleId(s.getId()).stream()
            .map(p -> new PaymentDto(p.getMethod(), p.getAmount())).toList();
        return new SaleDto(s.getId(), s.getSaleNumber(), s.getStatus(), s.getCustomerId(), customerName, s.getSubtotal(),
            s.getDiscount(), s.getTotal(), s.getAmountPaid(), s.getAmountOwed(), s.getNote(), s.getCreatedAt(),
            s.getRefundedAt(), items, pays, s.getBranchId(), s.getRefundedAmount(), s.getChannel(), s.getVoidReason());
    }

    private static String displayName(Product p, ProductVariant v) {
        return v.getLabel() == null ? p.getName() : p.getName() + " (" + v.getLabel() + ")";
    }
}
