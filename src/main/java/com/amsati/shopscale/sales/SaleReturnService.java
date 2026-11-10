package com.amsati.shopscale.sales;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.common.PaymentMethod;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.finance.FinanceService;
import com.amsati.shopscale.inventory.MovementType;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.security.Tenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns after a sale: partial refund, full refund and void. The value of returned goods is worked out on the server from
 * the original line prices (less each line's share of the sale discount). Money is paid back first by cancelling any unpaid
 * balance the customer still has on the sale, and only the rest goes out through the chosen payment method.
 */
@Service
@RequiredArgsConstructor
public class SaleReturnService {
    private final SaleRepository sales;
    private final SaleItemRepository saleItems;
    private final SalePaymentRepository salePayments;
    private final SaleRefundRepository refunds;
    private final SaleRefundItemRepository refundItems;
    private final ProductVariantRepository variants;
    private final CustomerRepository customers;
    private final StockService stock;
    private final FinanceService finance;
    private final SaleService saleService;
    private final AuditService audit;

    public record Line(@NotNull UUID saleItemId, @NotNull @DecimalMin(value = "0.001", message = "Quantity must be greater than zero") BigDecimal quantity,
                       Boolean returnToStock) {}
    public record RefundRequest(@NotEmpty(message = "Choose what is being returned") @Valid List<Line> lines, PaymentMethod method,
                                @Size(max = 300) String reason) {}
    public record VoidRequest(@NotNull @Size(min = 3, max = 300, message = "Say why this sale is being voided") String reason) {}

    @Transactional
    public SaleService.SaleDto refundPartial(UUID saleId, RefundRequest r) {
        Sale sale = lock(saleId);
        List<SaleItem> items = saleItems.findBySaleId(saleId);
        Map<UUID, SaleItem> byId = items.stream().collect(Collectors.toMap(SaleItem::getId, i -> i));
        List<Line> lines = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Line l : r.lines()) {
            if (!byId.containsKey(l.saleItemId()) || !seen.add(l.saleItemId())) throw ApiException.badRequest("That item is not on this sale");
            lines.add(l);
        }
        return apply(sale, items, lines, r.method() == null ? PaymentMethod.CASH : r.method(), r.reason(), false);
    }

    /** Refunds everything that has not already been returned. */
    @Transactional
    public SaleService.SaleDto refundAll(UUID saleId, String reason) {
        Sale sale = lock(saleId);
        List<SaleItem> items = saleItems.findBySaleId(saleId);
        List<Line> lines = items.stream().filter(i -> i.getQuantity().subtract(i.getQuantityRefunded()).signum() > 0)
            .map(i -> new Line(i.getId(), i.getQuantity().subtract(i.getQuantityRefunded()), true)).toList();
        if (lines.isEmpty()) throw ApiException.conflict("Everything on this sale has already been returned");
        return apply(sale, items, lines, null, reason, true);
    }

    @Transactional
    public SaleService.SaleDto voidSale(UUID saleId, VoidRequest r) {
        Sale sale = lock(saleId);
        if (sale.getRefundedAmount().signum() > 0) throw ApiException.conflict("This sale already has returns. Refund the rest instead of voiding it.");
        SaleService.SaleDto dto = refundAll(saleId, "Void: " + r.reason().trim());
        sale = sales.findByIdAndBusinessId(saleId, Tenant.businessId()).orElseThrow();
        sale.setStatus(SaleStatus.VOIDED);
        sale.setVoidReason(r.reason().trim());
        sale.setVoidedBy(Tenant.userId());
        audit.record(sale.getBusinessId(), Tenant.userId(), "SALE_VOIDED", "sale", saleId, r.reason().trim());
        return saleService.view(sale);
    }

    private Sale lock(UUID saleId) {
        UUID bid = Tenant.businessId();
        Sale sale = sales.findForUpdate(saleId, bid).orElseThrow(() -> ApiException.notFound("Sale not found"));
        if (sale.getStatus() != SaleStatus.COMPLETED) throw ApiException.conflict("This sale has already been " + sale.getStatus().name().toLowerCase());
        return sale;
    }

    private SaleService.SaleDto apply(Sale sale, List<SaleItem> items, List<Line> lines, PaymentMethod method, String reason, boolean all) {
        UUID bid = sale.getBusinessId();
        UUID uid = Tenant.userId();
        Map<UUID, SaleItem> byId = items.stream().collect(Collectors.toMap(SaleItem::getId, i -> i));
        Map<UUID, ProductVariant> vs = variants.lockAll(bid, items.stream().map(SaleItem::getVariantId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        // A line's share of the sale discount is returned with it.
        BigDecimal factor = sale.getSubtotal().signum() == 0 ? BigDecimal.ONE
            : sale.getTotal().divide(sale.getSubtotal(), 10, RoundingMode.HALF_UP);

        BigDecimal value = BigDecimal.ZERO, cost = BigDecimal.ZERO;
        SaleRefund rf = new SaleRefund();
        rf.setBusinessId(bid);
        rf.setSaleId(sale.getId());
        rf.setAmount(BigDecimal.ZERO);
        rf.setCashRefunded(BigDecimal.ZERO);
        rf.setOwedReduced(BigDecimal.ZERO);
        rf.setReason(reason == null || reason.isBlank() ? null : reason.trim());
        rf.setCreatedBy(uid);
        refunds.save(rf);
        String ref = "REFUND-" + sale.getSaleNumber();
        for (Line l : lines) {
            SaleItem it = byId.get(l.saleItemId());
            BigDecimal left = it.getQuantity().subtract(it.getQuantityRefunded());
            if (l.quantity().compareTo(left) > 0) throw ApiException.badRequest("Only " + left.stripTrailingZeros().toPlainString() + " of " + it.getProductName() + " can still be returned");
            BigDecimal lineValue = Money.scale(it.getUnitPrice().multiply(l.quantity()).multiply(factor));
            value = value.add(lineValue);
            cost = cost.add(it.getUnitCost().multiply(l.quantity()));
            it.setQuantityRefunded(it.getQuantityRefunded().add(l.quantity()));
            boolean back = l.returnToStock() == null || l.returnToStock();
            ProductVariant v = vs.get(it.getVariantId());
            if (back && v != null) stock.apply(v, sale.getBranchId(), MovementType.REFUND, l.quantity(), "Returned by customer", ref, uid, true);
            SaleRefundItem ri = new SaleRefundItem();
            ri.setRefundId(rf.getId());
            ri.setSaleItemId(it.getId());
            ri.setQuantity(l.quantity());
            ri.setAmount(lineValue);
            ri.setReturnedToStock(back);
            refundItems.save(ri);
        }
        value = Money.scale(value);
        BigDecimal remainingValue = sale.getTotal().subtract(sale.getRefundedAmount());
        if (all || value.compareTo(remainingValue) > 0) value = remainingValue;   // rounding never refunds more than was sold

        // 1) cancel what the customer still owes on this sale, 2) pay back the rest.
        BigDecimal owedReduced = value.min(sale.getAmountOwed());
        BigDecimal cash = value.subtract(owedReduced).min(sale.getAmountPaid());
        if (owedReduced.signum() > 0 && sale.getCustomerId() != null) {
            customers.findForUpdate(sale.getCustomerId(), bid).ifPresent(c -> {
                c.setBalance(c.getBalance().subtract(owedReduced));
                c.setUpdatedAt(Instant.now());
            });
        }
        if (cash.signum() > 0) {
            if (all) {
                BigDecimal left = cash;
                for (SalePayment sp : salePayments.findBySaleId(sale.getId())) {
                    if (left.signum() <= 0) break;
                    BigDecimal part = sp.getAmount().min(left);
                    finance.record(bid, sp.getMethod(), false, part, "REFUND", "SALE", sale.getId(), "Refund of sale #" + sale.getSaleNumber(), uid, sale.getBranchId());
                    left = left.subtract(part);
                }
            } else {
                finance.record(bid, method, false, cash, "REFUND", "SALE", sale.getId(), "Refund of sale #" + sale.getSaleNumber(), uid, sale.getBranchId());
            }
        }
        sale.setAmountOwed(sale.getAmountOwed().subtract(owedReduced));
        sale.setAmountPaid(sale.getAmountPaid().subtract(cash));
        sale.setRefundedAmount(sale.getRefundedAmount().add(value));
        sale.setRefundedCost(sale.getRefundedCost().add(Money.scale(cost)));
        boolean fullyReturned = items.stream().allMatch(i -> i.getQuantity().compareTo(i.getQuantityRefunded()) <= 0);
        if (fullyReturned) {
            sale.setStatus(SaleStatus.REFUNDED);
            sale.setRefundedAt(Instant.now());
        }
        rf.setAmount(value);
        rf.setCashRefunded(cash);
        rf.setOwedReduced(owedReduced);
        rf.setMethod(all ? null : method);
        audit.record(bid, uid, fullyReturned ? "SALE_REFUNDED" : "SALE_PART_REFUNDED", "sale", sale.getId(), ref + " " + value.toPlainString());
        return saleService.view(sale);
    }
}
