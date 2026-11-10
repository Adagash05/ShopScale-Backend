package com.amsati.shopscale.purchasing;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import com.amsati.shopscale.unit.UnitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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

/** Orders to suppliers. Goods can arrive in several deliveries; each delivery becomes a normal purchase linked to the order. */
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {
    public static final String ORDERED = "ORDERED", PARTIAL = "PARTIALLY_RECEIVED", RECEIVED = "RECEIVED", CANCELLED = "CANCELLED", CLOSED = "CLOSED";

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderItemRepository orderItems;
    private final PurchaseRepository purchases;
    private final SupplierRepository suppliers;
    private final BusinessRepository businesses;
    private final ProductVariantRepository variants;
    private final PurchasingService purchasing;
    private final StockService stock;
    private final UnitService units;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    public record OrderItemRequest(@NotNull UUID variantId,
                                   @NotNull @DecimalMin(value = "0.001", message = "Quantity must be greater than zero") BigDecimal quantity,
                                   @NotNull @DecimalMin(value = "0", message = "Cost cannot be negative") BigDecimal unitCost) {}
    public record OrderRequest(@NotNull UUID supplierId, UUID branchId, LocalDate expectedDate, @Size(max = 500) String note,
                               @NotEmpty(message = "Add at least one product") @Valid List<OrderItemRequest> items) {}
    public record ReceiveLine(@NotNull UUID orderItemId,
                              @NotNull @DecimalMin(value = "0.001", message = "Quantity must be greater than zero") BigDecimal quantity,
                              @DecimalMin(value = "0", message = "Cost cannot be negative") BigDecimal unitCost) {}
    public record ReceiveRequest(@NotEmpty(message = "Enter what arrived") @Valid List<ReceiveLine> lines,
                                 @DecimalMin(value = "0", message = "Amount paid cannot be negative") BigDecimal amountPaid,
                                 @Size(max = 60) String supplierInvoice, @Size(max = 300) String note, @Size(max = 64) String clientRequestId) {}

    public record ItemDto(UUID id, UUID variantId, String productName, String variantLabel, String unit, BigDecimal quantityOrdered,
                          BigDecimal quantityReceived, BigDecimal unitCost) {}
    public record OrderDto(UUID id, long poNumber, UUID supplierId, String supplierName, UUID branchId, String status, LocalDate expectedDate,
                           String note, Instant createdAt, BigDecimal total, List<ItemDto> items) {}

    @Transactional
    public OrderDto create(OrderRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireWritable(bid);
        subscriptions.requireFeature(bid, "PURCHASING", "Purchase orders");
        Supplier supplier = suppliers.findByIdAndBusinessId(r.supplierId(), bid).orElseThrow(() -> ApiException.badRequest("Supplier not found"));
        Set<UUID> ids = new LinkedHashSet<>();
        for (OrderItemRequest i : r.items()) if (!ids.add(i.variantId())) throw ApiException.badRequest("List each product once. Add the quantities together.");
        Map<UUID, ProductVariant> byId = variants.lockAll(bid, ids).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        if (byId.size() != ids.size()) throw ApiException.badRequest("One of the products no longer exists");
        for (OrderItemRequest i : r.items()) units.requireValidQuantity(byId.get(i.variantId()), i.quantity());
        UUID branch = stock.resolveBranch(bid, r.branchId());

        Business b = businesses.findByIdForUpdate(bid).orElseThrow(() -> ApiException.notFound("Business not found"));
        b.setPoCounter(b.getPoCounter() + 1);
        PurchaseOrder o = new PurchaseOrder();
        o.setBusinessId(bid);
        o.setBranchId(branch);
        o.setPoNumber(b.getPoCounter());
        o.setSupplierId(supplier.getId());
        o.setStatus(ORDERED);
        o.setExpectedDate(r.expectedDate());
        o.setNote(r.note() == null || r.note().isBlank() ? null : r.note().trim());
        o.setCreatedBy(Tenant.userId());
        orders.save(o);
        for (OrderItemRequest i : r.items()) {
            PurchaseOrderItem it = new PurchaseOrderItem();
            it.setPurchaseOrderId(o.getId());
            it.setBusinessId(bid);
            it.setVariantId(i.variantId());
            it.setQuantityOrdered(i.quantity());
            it.setUnitCost(Money.scale(i.unitCost()));
            orderItems.save(it);
        }
        audit.record(bid, Tenant.userId(), "PO_CREATED", "purchase_order", o.getId(), "PO-" + o.getPoNumber());
        return toDto(o);
    }

    @Transactional
    public OrderDto receive(UUID id, ReceiveRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireWritable(bid);
        String requestId = r.clientRequestId() == null || r.clientRequestId().isBlank() ? null : r.clientRequestId();
        PurchaseOrder o = orders.findForUpdate(id, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (requestId != null && purchases.findByBusinessIdAndClientRequestId(bid, requestId).isPresent()) return toDto(o);
        if (!ORDERED.equals(o.getStatus()) && !PARTIAL.equals(o.getStatus())) throw ApiException.conflict("This order is " + o.getStatus().toLowerCase().replace('_', ' '));

        Map<UUID, PurchaseOrderItem> items = orderItems.findByPurchaseOrderId(o.getId()).stream().collect(Collectors.toMap(PurchaseOrderItem::getId, i -> i));
        List<PurchasingService.ItemRequest> lines = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (ReceiveLine l : r.lines()) {
            PurchaseOrderItem it = items.get(l.orderItemId());
            if (it == null || !seen.add(it.getId())) throw ApiException.badRequest("That product is not on this order");
            BigDecimal outstanding = it.getQuantityOrdered().subtract(it.getQuantityReceived());
            if (l.quantity().compareTo(outstanding) > 0) throw ApiException.badRequest("You can receive at most " + outstanding.stripTrailingZeros().toPlainString() + " more of that product");
            lines.add(new PurchasingService.ItemRequest(it.getVariantId(), l.quantity(), l.unitCost() != null ? l.unitCost() : it.getUnitCost()));
        }
        PurchasingService.PurchaseDto pu = purchasing.receive(new PurchasingService.PurchaseRequest(lines, o.getSupplierId(), r.amountPaid(),
            r.supplierInvoice() != null && !r.supplierInvoice().isBlank() ? r.supplierInvoice() : "PO-" + o.getPoNumber(), r.note(), requestId, o.getBranchId(), null));
        Purchase p = purchases.findByIdAndBusinessId(pu.id(), bid).orElseThrow();
        p.setPurchaseOrderId(o.getId());

        for (ReceiveLine l : r.lines()) {
            PurchaseOrderItem it = items.get(l.orderItemId());
            it.setQuantityReceived(it.getQuantityReceived().add(l.quantity()));
        }
        boolean all = items.values().stream().allMatch(i -> i.getQuantityReceived().compareTo(i.getQuantityOrdered()) >= 0);
        o.setStatus(all ? RECEIVED : PARTIAL);
        if (all) o.setClosedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "PO_RECEIVED", "purchase_order", o.getId(), "PO-" + o.getPoNumber() + (all ? " complete" : " partial"));
        return toDto(o);
    }

    /** Cancel an order nothing has arrived for, or close one that will not be delivered in full. */
    @Transactional
    public OrderDto cancelOrClose(UUID id) {
        UUID bid = Tenant.businessId();
        subscriptions.requireWritable(bid);
        PurchaseOrder o = orders.findForUpdate(id, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (ORDERED.equals(o.getStatus())) o.setStatus(CANCELLED);
        else if (PARTIAL.equals(o.getStatus())) o.setStatus(CLOSED);
        else throw ApiException.conflict("This order is already " + o.getStatus().toLowerCase().replace('_', ' '));
        o.setClosedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "PO_" + o.getStatus(), "purchase_order", o.getId(), "PO-" + o.getPoNumber());
        return toDto(o);
    }

    @Transactional(readOnly = true)
    public Page<OrderDto> list(int page, int size) {
        return orders.findByBusinessIdOrderByCreatedAtDesc(Tenant.businessId(), PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public OrderDto get(UUID id) {
        return toDto(orders.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Order not found")));
    }

    private OrderDto toDto(PurchaseOrder o) {
        List<PurchaseOrderItem> items = orderItems.findByPurchaseOrderId(o.getId());
        Map<UUID, ProductVariant> vs = variants.findAllById(items.stream().map(PurchaseOrderItem::getVariantId).toList()).stream()
            .collect(Collectors.toMap(ProductVariant::getId, v -> v));
        String supplier = suppliers.findByIdAndBusinessId(o.getSupplierId(), o.getBusinessId()).map(Supplier::getName).orElse(null);
        BigDecimal total = BigDecimal.ZERO;
        List<ItemDto> dtos = new ArrayList<>();
        for (PurchaseOrderItem i : items) {
            ProductVariant v = vs.get(i.getVariantId());
            total = total.add(i.getUnitCost().multiply(i.getQuantityOrdered()));
            dtos.add(new ItemDto(i.getId(), i.getVariantId(), v.getProduct().getName(), v.getLabel(), v.getUnit(), i.getQuantityOrdered(), i.getQuantityReceived(), i.getUnitCost()));
        }
        return new OrderDto(o.getId(), o.getPoNumber(), o.getSupplierId(), supplier, o.getBranchId(), o.getStatus(), o.getExpectedDate(), o.getNote(),
            o.getCreatedAt(), Money.scale(total), dtos);
    }
}
