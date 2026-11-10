package com.amsati.shopscale.insight;

import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.ProductStatus;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.DateRange;
import com.amsati.shopscale.customer.Customer;
import com.amsati.shopscale.customer.CustomerPaymentRepository;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.expense.ExpenseRepository;
import com.amsati.shopscale.purchasing.SupplierRepository;
import com.amsati.shopscale.sales.SaleItemRepository;
import com.amsati.shopscale.sales.SaleRepository;
import com.amsati.shopscale.sales.SaleStatus;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "What needs my attention?" Every insight is deterministic arithmetic on the business's own records using the
 * named thresholds in InsightRules, and each one says what it is based on and where to fix it. Nothing is predicted.
 */
@Service
@RequiredArgsConstructor
public class InsightService {
    private final BusinessRepository businesses;
    private final ProductVariantRepository variants;
    private final SaleItemRepository saleItems;
    private final SaleRepository sales;
    private final CustomerRepository customers;
    private final CustomerPaymentRepository customerPayments;
    private final ExpenseRepository expenses;
    private final SupplierRepository suppliers;
    private final AccessService access;

    public record Item(String label, String note, String path) {}
    public record Insight(String id, String severity, String title, String detail, String basis, String actionLabel,
                          String actionPath, List<Item> items) {}

    private static String naira(BigDecimal v) { return "₦" + String.format(Locale.US, "%,.0f", v); }

    private static String name(ProductVariant v) { return v.getLabel() == null ? v.getProduct().getName() : v.getProduct().getName() + " (" + v.getLabel() + ")"; }

    @Transactional(readOnly = true)
    public List<Insight> compute(String range, LocalDate from, LocalDate to) {
        UUID bid = Tenant.businessId();
        boolean fin = access.current().contains(Permission.DASHBOARD_FINANCIAL);
        Business b = businesses.findById(bid).orElseThrow();
        ZoneId zone = b.zone();
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(zone);
        DateRange r = DateRange.resolve(range, from, to, today);
        List<Insight> out = new ArrayList<>();

        // ---- stock: selling rate over the last 30 days ----
        Instant windowStart = now.minus(Duration.ofDays(InsightRules.SALES_WINDOW_DAYS));
        Map<UUID, BigDecimal> sold = new HashMap<>();
        saleItems.unitsSoldByVariant(bid, SaleStatus.COMPLETED, windowStart).forEach(u -> sold.put(u.getVariantId(), u.getUnits() == null ? BigDecimal.ZERO : u.getUnits()));
        List<ProductVariant> all = variants.activeWithProduct(bid, ProductStatus.ACTIVE);
        int w = InsightRules.SALES_WINDOW_DAYS;

        List<Item> outOfStock = new ArrayList<>();
        List<Item> soon = new ArrayList<>();
        List<ProductVariant> slow = new ArrayList<>();
        List<Item> weak = new ArrayList<>();
        int noCost = 0;
        for (ProductVariant v : all) {
            BigDecimal unitsD = sold.getOrDefault(v.getId(), BigDecimal.ZERO);
            double units = unitsD.doubleValue();
            double qty = v.getQuantity().doubleValue();
            String path = "/products/" + v.getProduct().getId();
            if (v.getQuantity().signum() <= 0 && units >= 1) {
                outOfStock.add(new Item(name(v), "Sold " + unitsD.stripTrailingZeros().toPlainString() + " in the last " + w + " days · buying " + Math.max(1, InsightRules.suggestedReorder(0, units, w)) + " would cover " + InsightRules.REORDER_COVER_DAYS + " days", path));
            } else if (InsightRules.runningOutSoon(qty, units, w)) {
                Double days = InsightRules.daysOfCover(qty, units, w);
                soon.add(new Item(name(v), v.getQuantity().stripTrailingZeros().toPlainString() + " left · about " + Math.max(1, Math.round(days)) + " days at the recent pace · suggest buying " + InsightRules.suggestedReorder(qty, units, w), path));
            }
            if (v.getQuantity().signum() > 0 && unitsD.signum() == 0 && v.getCreatedAt().isBefore(windowStart)) slow.add(v);
            if (InsightRules.weakMargin(v.getPrice(), v.getCost())) {
                weak.add(new Item(name(v), "Margin " + InsightRules.marginPercent(v.getPrice(), v.getCost()) + "% · price " + naira(v.getPrice()) + ", cost " + naira(v.getCost()), path));
            }
            if (v.getCost().signum() == 0) noCost++;
        }
        if (!outOfStock.isEmpty()) out.add(new Insight("out-of-stock-selling", "ATTENTION", outOfStock.size() + (outOfStock.size() == 1 ? " product is" : " products are") + " out of stock but still selling",
            "You are probably losing sales on these.", "Out of stock now and sold at least once in the last " + w + " days.", "Receive stock", "/purchases/new", top(outOfStock)));
        if (!soon.isEmpty()) out.add(new Insight("running-out", "WARNING", soon.size() + (soon.size() == 1 ? " product" : " products") + " will run out within a week",
            "At the recent pace they will not last.", "Stock covers " + InsightRules.STOCKOUT_HORIZON_DAYS + " days or fewer at the average of the last " + w + " days. Needs at least " + InsightRules.MIN_UNITS_FOR_RATE + " units sold to count. Suggested quantity covers " + InsightRules.REORDER_COVER_DAYS + " days.",
            "Receive stock", "/purchases/new", top(soon)));
        if (!slow.isEmpty()) {
            slow.sort(Comparator.comparing((ProductVariant v) -> v.getCost().multiply(v.getQuantity())).reversed());
            BigDecimal tied = slow.stream().map(v -> v.getCost().multiply(v.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Item> items = slow.stream().limit(5).map(v -> new Item(name(v), v.getQuantity().stripTrailingZeros().toPlainString() + " in stock" + (fin && v.getCost().signum() > 0 ? " · " + naira(v.getCost().multiply(v.getQuantity())) + " at cost" : ""), "/products/" + v.getProduct().getId())).toList();
            out.add(new Insight("slow-moving", "INFO", slow.size() + (slow.size() == 1 ? " product has" : " products have") + " not sold in " + w + " days",
                fin && tied.signum() > 0 ? "About " + naira(tied) + " of stock (at cost) is sitting on the shelf." : "These are taking up shelf space.",
                "In stock, added more than " + w + " days ago, and no completed sale in the last " + w + " days.", "See products", "/products", items));
        }
        if (fin && !weak.isEmpty()) out.add(new Insight("weak-margin", "WARNING", weak.size() + (weak.size() == 1 ? " product has" : " products have") + " a weak margin",
            "You keep very little, or lose money, on each one.", "Margin = (price − cost) ÷ price, below " + InsightRules.WEAK_MARGIN_PERCENT + "%. Based on the cost price you entered or the average cost from purchases.", "Review prices", "/products", top(weak)));
        if (fin && noCost > 0) out.add(new Insight("missing-cost", "INFO", noCost + (noCost == 1 ? " product has" : " products have") + " no cost price",
            "Your profit figure is overstated until costs are entered.", "Cost price is zero or empty. Receiving stock from a supplier sets it automatically.", "Add costs", "/products", List.of()));

        // ---- customers who owe you ----
        if (fin) {
            Map<UUID, Instant> last = new HashMap<>();
            sales.lastCreditSales(bid, SaleStatus.COMPLETED).forEach(c -> last.merge(c.getCustomerId(), c.getAt(), (a, x) -> a.isAfter(x) ? a : x));
            customerPayments.lastPayments(bid).forEach(p -> last.merge(p.getCustomerId(), p.getAt(), (a, x) -> a.isAfter(x) ? a : x));
            List<Customer> late = new ArrayList<>();
            for (Customer c : customers.owing(bid)) if (InsightRules.isOverdue(last.get(c.getId()), now)) late.add(c);
            if (!late.isEmpty()) {
                BigDecimal sum = late.stream().map(Customer::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
                out.add(new Insight("overdue-customers", "ATTENTION", late.size() + (late.size() == 1 ? " customer owes" : " customers owe") + " you " + naira(sum) + " with no activity for " + InsightRules.OVERDUE_DAYS + "+ days",
                    "Following up on these is the quickest way to improve your cash.", "Balance above zero and no credit sale or payment from them in the last " + InsightRules.OVERDUE_DAYS + " days.", "See who owes you", "/customers?owing=1",
                    late.stream().limit(5).map(c -> new Item(c.getName(), "Owes " + naira(c.getBalance()) + " · last activity " + ChronoUnit.DAYS.between(last.get(c.getId()), now) + " days ago", "/customers/" + c.getId())).toList()));
            }
            BigDecimal owedSuppliers = suppliers.totalOwed(bid);
            if (owedSuppliers != null && owedSuppliers.signum() > 0) {
                out.add(new Insight("owe-suppliers", "INFO", "You owe suppliers " + naira(owedSuppliers), "Plan for these payments so you do not lose supplier trust.",
                    "Sum of unpaid purchase balances across all suppliers, right now.", "See suppliers", "/suppliers", List.of()));
            }
        }

        // ---- compared with the period before (only for ranges long enough to mean something) ----
        long len = ChronoUnit.DAYS.between(r.from(), r.to()) + 1;
        if (len >= InsightRules.MIN_COMPARE_DAYS) {
            LocalDate prevTo = r.from().minusDays(1);
            DateRange prev = new DateRange(prevTo.minusDays(len - 1), prevTo);
            String basis = "Compared with the " + len + " days before (" + prev.from() + " to " + prev.to() + "). Only shown for ranges of " + InsightRules.MIN_COMPARE_DAYS + "+ days.";
            SaleRepository.SalesAggregate cur = sales.aggregate(bid, SaleStatus.COMPLETED, r.start(zone), r.endExclusive(zone));
            SaleRepository.SalesAggregate pre = sales.aggregate(bid, SaleStatus.COMPLETED, prev.start(zone), prev.endExclusive(zone));
            Double salesChange = InsightRules.changePercent(pre == null ? null : pre.getTotal(), cur == null ? null : cur.getTotal());
            if (salesChange != null && salesChange <= -InsightRules.SALES_DROP_PERCENT) {
                out.add(new Insight("sales-down", "WARNING", "Sales are down " + Math.round(-salesChange) + "%", "Worth finding out why: stock-outs, fewer customers, or a slow week.",
                    basis + " Alert when sales fall " + InsightRules.SALES_DROP_PERCENT + "% or more.", "See sales", "/more/sales?range=" + range, List.of()));
            }
            if (fin) {
                BigDecimal ce = expenses.sumBetween(bid, r.from(), r.to());
                BigDecimal pe = expenses.sumBetween(bid, prev.from(), prev.to());
                Double expChange = InsightRules.changePercent(pe, ce);
                if (expChange != null && expChange >= InsightRules.EXPENSE_RISE_PERCENT) {
                    out.add(new Insight("expenses-up", "WARNING", "Expenses are up " + Math.round(expChange) + "%", "You spent " + naira(ce) + " compared with " + naira(pe) + " before.",
                        basis + " Alert when expenses rise " + InsightRules.EXPENSE_RISE_PERCENT + "% or more.", "See expenses", "/expenses?range=" + range, List.of()));
                }
            }
        }
        return out;
    }

    private static List<Item> top(List<Item> items) { return items.size() <= 5 ? items : items.subList(0, 5); }
}
