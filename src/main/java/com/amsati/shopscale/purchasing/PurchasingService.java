package com.amsati.shopscale.purchasing;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.Product;
import com.amsati.shopscale.catalog.ProductRepository;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.catalog.ProductStatus;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.common.PaymentMethod;
import com.amsati.shopscale.inventory.MovementType;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buying stock from suppliers. One transaction ties together: the purchase record, stock received (with a stock
 * movement), the cost of each product (weighted average), and what the business now owes the supplier.
 * Purchases are not expenses: the money becomes a cost when the goods are sold, so counting it here too would double it.
 */
@Service
@RequiredArgsConstructor
public class PurchasingService {
    private final SupplierRepository suppliers;
    private final SupplierPaymentRepository supplierPayments;
    private final PurchaseRepository purchases;
    private final PurchaseItemRepository purchaseItems;
    private final BusinessRepository businesses;
    private final ProductVariantRepository variants;
    private final ProductRepository products;
    private final StockService stock;
    private final SubscriptionService subscriptions;
    private final AccessService access;
    private final AuditService audit;

    // ---------- requests / responses ----------
    public record SupplierRequest(@NotBlank @Size(max = 150) String name, @Size(max = 40) String phone,
                                  @Size(max = 255) String email, @Size(max = 500) String address, @Size(max = 1000) String notes) {}

    public record SupplierPaymentRequest(@NotNull @DecimalMin(value = "0.01", message = "Enter an amount above zero") BigDecimal amount,
                                         @NotNull PaymentMethod method, @Size(max = 300) String note) {}

    public record ItemRequest(@NotNull UUID variantId, @Min(value = 1, message = "Quantity must be at least 1") int quantity,
                              @NotNull @DecimalMin(value = "0", message = "Cost cannot be negative") BigDecimal unitCost) {}

    public record PurchaseRequest(@NotEmpty(message = "Add at least one product") @Valid List<ItemRequest> items, UUID supplierId,
                                  @DecimalMin(value = "0", message = "Amount paid cannot be negative") BigDecimal amountPaid,
                                  @Size(max = 60) String reference, @Size(max = 300) String note, @Size(max = 64) String clientRequestId) {}

    public record SupplierDto(UUID id, String name, String phone, String email, String address, String notes, BigDecimal balance) {}
    public record SupplierPaymentDto(UUID id, BigDecimal amount, PaymentMethod method, String note, Instant createdAt) {}
    public record SupplierDetail(SupplierDto supplier, List<SupplierPaymentDto> payments, List<PurchaseSummary> purchases) {}
    public record PurchaseItemDto(UUID variantId, String productName, String variantLabel, int quantity, BigDecimal unitCost, BigDecimal lineTotal) {}
    public record PurchaseDto(UUID id, long purchaseNumber, UUID supplierId, String supplierName, String reference, BigDecimal total,
                              BigDecimal amountPaid, BigDecimal amountOwed, String note, Instant createdAt, List<PurchaseItemDto> items) {}

    private static String like(String q) {
        return "%" + (q == null ? "" : q.trim().toLowerCase().replace("%", "").replace("_", "")) + "%";
    }

    /** What the business owes is financial: only people who may see money get the figure. */
    private boolean seesMoney() {
        Set<Permission> p = access.current();
        return p.contains(Permission.FINANCE_OVERVIEW) || p.contains(Permission.SUPPLIER_PAYMENTS_RECORD) || p.contains(Permission.DASHBOARD_FINANCIAL);
    }

    private SupplierDto dto(Supplier s, boolean money) {
        return new SupplierDto(s.getId(), s.getName(), s.getPhone(), s.getEmail(), s.getAddress(), s.getNotes(), money ? Money.scale(s.getBalance()) : null);
    }

    // ---------- suppliers ----------
    @Transactional(readOnly = true)
    public Page<SupplierDto> listSuppliers(String q, int page, int size) {
        boolean money = seesMoney();
        return suppliers.search(Tenant.businessId(), like(q), PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)))
            .map(s -> dto(s, money));
    }

    @Transactional
    public SupplierDto createSupplier(SupplierRequest r) {
        Supplier s = new Supplier();
        s.setBusinessId(Tenant.businessId());
        applySupplier(s, r);
        suppliers.save(s);
        audit.record(s.getBusinessId(), Tenant.userId(), "SUPPLIER_CREATED", "supplier", s.getId(), s.getName());
        return dto(s, seesMoney());
    }

    @Transactional
    public SupplierDto updateSupplier(UUID id, SupplierRequest r) {
        Supplier s = suppliers.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Supplier not found"));
        applySupplier(s, r);
        s.setUpdatedAt(Instant.now());
        return dto(s, seesMoney());
    }

    private void applySupplier(Supplier s, SupplierRequest r) {
        s.setName(r.name().trim());
        s.setPhone(blank(r.phone()));
        s.setEmail(blank(r.email()));
        s.setAddress(blank(r.address()));
        s.setNotes(blank(r.notes()));
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    @Transactional(readOnly = true)
    public SupplierDetail supplierDetail(UUID id) {
        UUID bid = Tenant.businessId();
        boolean money = seesMoney();
        Supplier s = suppliers.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Supplier not found"));
        List<SupplierPaymentDto> pays = money ? supplierPayments.findTop50ByBusinessIdAndSupplierIdOrderByCreatedAtDesc(bid, id).stream()
            .map(p -> new SupplierPaymentDto(p.getId(), p.getAmount(), p.getMethod(), p.getNote(), p.getCreatedAt())).toList() : List.of();
        List<PurchaseSummary> recent = access.current().contains(Permission.PURCHASES_VIEW)
            ? purchases.forSupplier(bid, id, PageRequest.of(0, 20)).getContent() : List.of();
        return new SupplierDetail(dto(s, money), pays, recent);
    }

    /** Money paid to a supplier against what is owed. */
    @Transactional
    public SupplierDetail paySupplier(UUID id, SupplierPaymentRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireFeature(bid, "SUPPLIER_BALANCES", "Tracking what you owe suppliers");
        Supplier s = suppliers.findForUpdate(id, bid).orElseThrow(() -> ApiException.notFound("Supplier not found"));
        BigDecimal amount = Money.scale(r.amount());
        if (amount.compareTo(s.getBalance()) > 0) {
            throw ApiException.badRequest("That is more than you owe this supplier (" + Money.scale(s.getBalance()).toPlainString() + ")");
        }
        s.setBalance(s.getBalance().subtract(amount));
        s.setUpdatedAt(Instant.now());
        SupplierPayment p = new SupplierPayment();
        p.setBusinessId(bid);
        p.setSupplierId(id);
        p.setAmount(amount);
        p.setMethod(r.method());
        p.setNote(r.note());
        p.setCreatedBy(Tenant.userId());
        supplierPayments.save(p);
        audit.record(bid, Tenant.userId(), "SUPPLIER_PAYMENT", "supplier", id, amount.toPlainString());
        return supplierDetail(id);
    }

    // ---------- purchases ----------
    @Transactional
    public PurchaseDto receive(PurchaseRequest req) {
        UUID bid = Tenant.businessId();
        UUID userId = Tenant.userId();

        String requestId = req.clientRequestId() == null || req.clientRequestId().isBlank() ? null : req.clientRequestId();
        if (requestId != null) {
            Optional<Purchase> prior = purchases.findByBusinessIdAndClientRequestId(bid, requestId);
            if (prior.isPresent()) return toDto(prior.get());
        }

        Set<UUID> ids = new HashSet<>();
        for (ItemRequest i : req.items()) {
            if (!ids.add(i.variantId())) throw ApiException.badRequest("List each product once. Add the quantities together.");
        }

        Business business = businesses.findByIdForUpdate(bid).orElseThrow(() -> ApiException.notFound("Business not found"));
        List<ProductVariant> locked = variants.lockAll(bid, ids);
        if (locked.size() != ids.size()) throw ApiException.badRequest("One of the products no longer exists");
        Map<UUID, ProductVariant> byId = locked.stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        Map<UUID, Product> productById = products.findAllById(locked.stream().map(v -> v.getProduct().getId()).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(Product::getId, p -> p));

        BigDecimal total = BigDecimal.ZERO;
        for (ItemRequest i : req.items()) {
            ProductVariant v = byId.get(i.variantId());
            Product p = productById.get(v.getProduct().getId());
            if (!v.isActive() || p.getStatus() != ProductStatus.ACTIVE) {
                throw ApiException.badRequest(p.getName() + " is archived. Restore it before receiving stock.");
            }
            total = total.add(i.unitCost().multiply(BigDecimal.valueOf(i.quantity())));
        }
        total = Money.scale(total);
        BigDecimal paid = Money.scale(req.amountPaid());
        if (paid.compareTo(total) > 0) throw ApiException.badRequest("The amount paid is more than the purchase total");
        BigDecimal owed = total.subtract(paid);

        Supplier supplier = null;
        if (req.supplierId() != null) {
            supplier = suppliers.findForUpdate(req.supplierId(), bid).orElseThrow(() -> ApiException.badRequest("Supplier not found"));
        }
        if (owed.signum() > 0) {
            if (supplier == null) throw ApiException.badRequest("Choose a supplier when you have not paid in full");
            subscriptions.requireFeature(bid, "SUPPLIER_BALANCES", "Tracking what you owe suppliers");
        }

        business.setPurchaseCounter(business.getPurchaseCounter() + 1);
        Purchase pu = new Purchase();
        pu.setBusinessId(bid);
        pu.setPurchaseNumber(business.getPurchaseCounter());
        pu.setSupplierId(supplier == null ? null : supplier.getId());
        pu.setReference(blank(req.reference()));
        pu.setTotal(total);
        pu.setAmountPaid(paid);
        pu.setAmountOwed(owed);
        pu.setNote(req.note());
        pu.setClientRequestId(requestId);
        pu.setCreatedBy(userId);
        purchases.save(pu);

        String ref = "PURCHASE-" + pu.getPurchaseNumber();
        for (ItemRequest i : req.items()) {
            ProductVariant v = byId.get(i.variantId());
            Product p = productById.get(v.getProduct().getId());
            // Cost first (uses the quantity on hand before this delivery), then the stock itself.
            v.setCost(CostMath.weightedAverage(v.getQuantity(), v.getCost(), i.quantity(), i.unitCost()));
            stock.apply(v, MovementType.RESTOCK, i.quantity(), "Purchase #" + pu.getPurchaseNumber(), ref, userId, true);
            PurchaseItem it = new PurchaseItem();
            it.setPurchaseId(pu.getId());
            it.setBusinessId(bid);
            it.setVariantId(v.getId());
            it.setProductName(p.getName());
            it.setVariantLabel(v.getLabel());
            it.setQuantity(i.quantity());
            it.setUnitCost(Money.scale(i.unitCost()));
            it.setLineTotal(Money.scale(i.unitCost().multiply(BigDecimal.valueOf(i.quantity()))));
            purchaseItems.save(it);
        }
        if (supplier != null && owed.signum() > 0) {
            supplier.setBalance(supplier.getBalance().add(owed));
            supplier.setUpdatedAt(Instant.now());
        }
        audit.record(bid, userId, "PURCHASE_RECEIVED", "purchase", pu.getId(), ref + " total " + total.toPlainString());
        return toDto(pu);
    }

    @Transactional(readOnly = true)
    public Page<PurchaseSummary> listPurchases(int page, int size) {
        return purchases.list(Tenant.businessId(), PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    @Transactional(readOnly = true)
    public PurchaseDto getPurchase(UUID id) {
        return toDto(purchases.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Purchase not found")));
    }

    private PurchaseDto toDto(Purchase p) {
        String supplierName = p.getSupplierId() == null ? null
            : suppliers.findByIdAndBusinessId(p.getSupplierId(), p.getBusinessId()).map(Supplier::getName).orElse(null);
        List<PurchaseItemDto> items = purchaseItems.findByPurchaseId(p.getId()).stream()
            .map(i -> new PurchaseItemDto(i.getVariantId(), i.getProductName(), i.getVariantLabel(), i.getQuantity(), i.getUnitCost(), i.getLineTotal())).toList();
        return new PurchaseDto(p.getId(), p.getPurchaseNumber(), p.getSupplierId(), supplierName, p.getReference(), p.getTotal(),
            p.getAmountPaid(), p.getAmountOwed(), p.getNote(), p.getCreatedAt(), items);
    }
}
