package com.amsati.shopscale.sales;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.common.PaymentMethod;
import com.amsati.shopscale.customer.Customer;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.finance.FinanceService;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
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

/**
 * Quotations and customer orders. Prices come from the catalogue when the document is created and are then fixed.
 * Converting a document records an ordinary sale (stock is taken at that moment). A deposit taken on an order is received
 * when the order is created and is counted as already paid when the order is turned into a sale.
 */
@Service
@RequiredArgsConstructor
public class SalesDocumentService {
    private final SalesDocumentRepository docs;
    private final SalesDocumentItemRepository docItems;
    private final BusinessRepository businesses;
    private final CustomerRepository customers;
    private final ProductVariantRepository variants;
    private final StockService stock;
    private final UnitService units;
    private final SaleService saleService;
    private final FinanceService finance;
    private final AccessService access;
    private final AuditService audit;

    public record ItemRequest(@NotNull UUID variantId, @NotNull @DecimalMin(value = "0.001", message = "Quantity must be greater than zero") BigDecimal quantity) {}
    public record DocRequest(@NotNull String kind, UUID customerId, UUID branchId, LocalDate validUntil, LocalDate deliveryDate,
                             @DecimalMin(value = "0", message = "Discount cannot be negative") BigDecimal discount,
                             @DecimalMin(value = "0", message = "Deposit cannot be negative") BigDecimal depositAmount, PaymentMethod depositMethod,
                             @Size(max = 500) String note, @NotEmpty(message = "Add at least one item") @Valid List<ItemRequest> items) {}
    public record ConvertRequest(@Valid List<SaleService.PayRequest> payments, @Size(max = 64) String clientRequestId) {}
    public record ItemDto(UUID variantId, String productName, String variantLabel, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}
    public record DocDto(UUID id, String kind, long number, UUID customerId, String customerName, String status, LocalDate validUntil,
                         LocalDate deliveryDate, BigDecimal subtotal, BigDecimal discount, BigDecimal total, BigDecimal depositAmount,
                         String note, UUID saleId, Instant createdAt, List<ItemDto> items) {}

    @Transactional
    public DocDto create(DocRequest r) {
        UUID bid = Tenant.businessId();
        UUID uid = Tenant.userId();
        if (!"QUOTE".equals(r.kind()) && !"ORDER".equals(r.kind())) throw ApiException.badRequest("Choose quotation or order");
        boolean order = "ORDER".equals(r.kind());
        Set<UUID> ids = new LinkedHashSet<>();
        for (ItemRequest i : r.items()) if (!ids.add(i.variantId())) throw ApiException.badRequest("List each product once. Add the quantities together.");
        Map<UUID, ProductVariant> byId = variants.findAllById(ids).stream().filter(v -> v.getBusinessId().equals(bid)).collect(Collectors.toMap(ProductVariant::getId, v -> v));
        if (byId.size() != ids.size()) throw ApiException.badRequest("One of the products no longer exists");
        UUID branch = stock.resolveBranch(bid, r.branchId());
        if (r.customerId() != null) customers.findByIdAndBusinessId(r.customerId(), bid).orElseThrow(() -> ApiException.badRequest("Customer not found"));
        if (order && r.customerId() == null) throw ApiException.badRequest("Choose the customer this order is for");

        BigDecimal subtotal = BigDecimal.ZERO;
        List<SalesDocumentItem> lines = new ArrayList<>();
        for (ItemRequest i : r.items()) {
            ProductVariant v = byId.get(i.variantId());
            units.requireValidQuantity(v, i.quantity());
            SalesDocumentItem it = new SalesDocumentItem();
            it.setBusinessId(bid);
            it.setVariantId(v.getId());
            it.setProductName(v.getProduct().getName());
            it.setVariantLabel(v.getLabel());
            it.setQuantity(i.quantity());
            it.setUnitPrice(v.getPrice());
            it.setLineTotal(Money.scale(v.getPrice().multiply(i.quantity())));
            subtotal = subtotal.add(it.getLineTotal());
            lines.add(it);
        }
        BigDecimal discount = Money.scale(r.discount());
        if (discount.signum() > 0) access.require(Permission.SALES_DISCOUNT);
        if (discount.compareTo(subtotal) > 0) throw ApiException.badRequest("The discount is more than the total of the items");
        BigDecimal total = subtotal.subtract(discount);
        BigDecimal deposit = Money.scale(r.depositAmount());
        if (deposit.signum() > 0 && !order) throw ApiException.badRequest("Deposits are taken on orders, not quotations");
        if (deposit.compareTo(total) > 0) throw ApiException.badRequest("The deposit is more than the order total");

        Business b = businesses.findByIdForUpdate(bid).orElseThrow(() -> ApiException.notFound("Business not found"));
        b.setDocumentCounter(b.getDocumentCounter() + 1);
        SalesDocument d = new SalesDocument();
        d.setBusinessId(bid);
        d.setBranchId(branch);
        d.setKind(r.kind());
        d.setDocNumber(b.getDocumentCounter());
        d.setCustomerId(r.customerId());
        d.setValidUntil(r.validUntil());
        d.setDeliveryDate(r.deliveryDate());
        d.setSubtotal(Money.scale(subtotal));
        d.setDiscount(discount);
        d.setTotal(total);
        d.setDepositAmount(deposit);
        d.setDepositMethod(deposit.signum() > 0 ? (r.depositMethod() == null ? PaymentMethod.CASH : r.depositMethod()).name() : null);
        d.setNote(r.note() == null || r.note().isBlank() ? null : r.note().trim());
        d.setCreatedBy(uid);
        docs.save(d);
        for (SalesDocumentItem it : lines) { it.setDocumentId(d.getId()); docItems.save(it); }
        if (deposit.signum() > 0) {
            finance.record(bid, PaymentMethod.valueOf(d.getDepositMethod()), true, deposit, "SALE", "ORDER", d.getId(), "Deposit for order #" + d.getDocNumber(), uid, branch);
        }
        audit.record(bid, uid, order ? "ORDER_CREATED" : "QUOTE_CREATED", "sales_document", d.getId(), "#" + d.getDocNumber());
        return toDto(d);
    }

    /** Quote -> order (customer accepted), no stock or money moves. */
    @Transactional
    public DocDto accept(UUID id) {
        SalesDocument d = docs.findForUpdate(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Document not found"));
        if (!"QUOTE".equals(d.getKind()) || !"OPEN".equals(d.getStatus())) throw ApiException.conflict("Only an open quotation can be accepted");
        d.setStatus("ACCEPTED");
        return toDto(d);
    }

    @Transactional
    public DocDto cancel(UUID id, boolean refundDeposit) {
        UUID bid = Tenant.businessId();
        SalesDocument d = docs.findForUpdate(id, bid).orElseThrow(() -> ApiException.notFound("Document not found"));
        if ("CONVERTED".equals(d.getStatus()) || "CANCELLED".equals(d.getStatus())) throw ApiException.conflict("This document is already " + d.getStatus().toLowerCase());
        d.setStatus("CANCELLED");
        if ("ORDER".equals(d.getKind()) && d.getDepositAmount().signum() > 0) {
            if (!refundDeposit) throw ApiException.badRequest("This order has a deposit. Say whether it is being paid back.");
            access.require(Permission.SALES_REFUND);
            finance.record(bid, PaymentMethod.valueOf(d.getDepositMethod()), false, d.getDepositAmount(), "REFUND", "ORDER", d.getId(),
                "Deposit returned for order #" + d.getDocNumber(), Tenant.userId(), d.getBranchId());
        }
        audit.record(bid, Tenant.userId(), "DOCUMENT_CANCELLED", "sales_document", id, "#" + d.getDocNumber());
        return toDto(d);
    }

    /** Turn a quotation or order into a real sale. Quoted prices are honoured (the difference is recorded as a discount). */
    @Transactional
    public DocDto convert(UUID id, ConvertRequest r) {
        UUID bid = Tenant.businessId();
        SalesDocument d = docs.findForUpdate(id, bid).orElseThrow(() -> ApiException.notFound("Document not found"));
        if ("CONVERTED".equals(d.getStatus()) || "CANCELLED".equals(d.getStatus()) || "REJECTED".equals(d.getStatus())) throw ApiException.conflict("This document is already " + d.getStatus().toLowerCase());
        List<SalesDocumentItem> lines = docItems.findByDocumentId(d.getId());
        Map<UUID, ProductVariant> byId = variants.findAllById(lines.stream().map(SalesDocumentItem::getVariantId).toList()).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        BigDecimal current = BigDecimal.ZERO;
        List<SaleService.ItemRequest> items = new ArrayList<>();
        for (SalesDocumentItem l : lines) {
            ProductVariant v = byId.get(l.getVariantId());
            current = current.add(v.getPrice().multiply(l.getQuantity()));
            items.add(new SaleService.ItemRequest(l.getVariantId(), l.getQuantity(), null));
        }
        // Honour the quoted total: if prices have gone up since, the gap is given as a discount. If they went down, today's lower price applies.
        BigDecimal discount = Money.scale(current).subtract(d.getTotal()).max(BigDecimal.ZERO);
        String requestId = r != null && r.clientRequestId() != null && !r.clientRequestId().isBlank() ? r.clientRequestId() : "DOC-" + d.getId();
        SaleService.SaleDto sale = saleService.createInternal(new SaleService.SaleRequest(items, discount, d.getCustomerId(),
            r == null ? null : r.payments(), "From " + ("ORDER".equals(d.getKind()) ? "order #" : "quotation #") + d.getDocNumber(), requestId, d.getBranchId()),
            "ORDER".equals(d.getKind()) ? "ORDER" : "POS", d.getDepositAmount(), d.getDepositMethod() == null ? null : PaymentMethod.valueOf(d.getDepositMethod()), true);
        d.setStatus("CONVERTED");
        d.setSaleId(sale.id());
        audit.record(bid, Tenant.userId(), "DOCUMENT_CONVERTED", "sales_document", id, "#" + d.getDocNumber() + " -> sale " + sale.saleNumber());
        return toDto(d);
    }

    @Transactional(readOnly = true)
    public Page<DocDto> list(String kind, int page, int size) {
        return docs.findByBusinessIdAndKindOrderByCreatedAtDesc(Tenant.businessId(), kind, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public DocDto get(UUID id) {
        return toDto(docs.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Document not found")));
    }

    private DocDto toDto(SalesDocument d) {
        String cname = d.getCustomerId() == null ? null : customers.findByIdAndBusinessId(d.getCustomerId(), d.getBusinessId()).map(Customer::getName).orElse(null);
        String status = d.getStatus();
        if ("OPEN".equals(status) && d.getValidUntil() != null && d.getValidUntil().isBefore(LocalDate.now())) status = "EXPIRED";
        List<ItemDto> items = docItems.findByDocumentId(d.getId()).stream()
            .map(i -> new ItemDto(i.getVariantId(), i.getProductName(), i.getVariantLabel(), i.getQuantity(), i.getUnitPrice(), i.getLineTotal())).toList();
        return new DocDto(d.getId(), d.getKind(), d.getDocNumber(), d.getCustomerId(), cname, status, d.getValidUntil(), d.getDeliveryDate(), d.getSubtotal(),
            d.getDiscount(), d.getTotal(), d.getDepositAmount(), d.getNote(), d.getSaleId(), d.getCreatedAt(), items);
    }
}
