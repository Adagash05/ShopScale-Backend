package com.amsati.shopscale.dashboard;

import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.Product;
import com.amsati.shopscale.catalog.ProductRepository;
import com.amsati.shopscale.catalog.ProductStatus;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.DateRange;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.customer.Customer;
import com.amsati.shopscale.customer.CustomerPayment;
import com.amsati.shopscale.customer.CustomerPaymentRepository;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.expense.Expense;
import com.amsati.shopscale.expense.ExpenseRepository;
import com.amsati.shopscale.inventory.LowStockDto;
import com.amsati.shopscale.purchasing.PurchaseRepository;
import com.amsati.shopscale.purchasing.PurchaseSummary;
import com.amsati.shopscale.purchasing.SupplierRepository;
import com.amsati.shopscale.sales.SaleItemRepository;
import com.amsati.shopscale.sales.SalePaymentRepository;
import com.amsati.shopscale.sales.SaleRepository;
import com.amsati.shopscale.sales.SaleStatus;
import com.amsati.shopscale.sales.SaleSummary;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the dashboard shows beyond DashboardService.summary: comparison with the previous period, the sales trend with the
 * previous period alongside, best products and customers, how customers paid, expenses by category, stock needing attention,
 * debts, and the latest transactions. Every figure is calculated here from this business's own records (all queries are scoped
 * by the signed-in business) and nothing is estimated in the browser. Money and profit figures are only filled in for people who
 * may see them (DASHBOARD_FINANCIAL); for everyone else those fields are null.
 *
 * Definitions: sales = completed sales minus refunds; money received = payments taken on those sales plus customer repayments
 * recorded in the period; gross profit = sales minus the cost of what was sold; customer debt and supplier balances are as of today.
 */
@Service
@RequiredArgsConstructor
public class DashboardOverviewService {
    private final DashboardService summaryService;
    private final BusinessRepository businesses;
    private final AccessService access;
    private final SaleRepository sales;
    private final SaleItemRepository saleItems;
    private final SalePaymentRepository salePayments;
    private final CustomerRepository customers;
    private final CustomerPaymentRepository customerPayments;
    private final ExpenseRepository expenses;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final PurchaseRepository purchases;
    private final SupplierRepository suppliers;
    private final com.amsati.shopscale.invoice.InvoiceReporting invoiceReporting;

    public record Change(BigDecimal previous, BigDecimal changePercent) {}
    public record Comparison(boolean available, LocalDate from, LocalDate to, Change sales, Change moneyReceived, Change grossProfit, Change expenses) {}
    public record TrendPoint(LocalDate date, BigDecimal sales, BigDecimal previousSales) {}
    public record Trend(List<TrendPoint> daily, List<DashboardMath.Bucket> weekly, List<DashboardMath.Bucket> monthly) {}
    public record BestProduct(String name, String imageUrl, BigDecimal units, BigDecimal revenue, BigDecimal shareOfSales) {}
    public record BestCustomer(UUID id, String name, long transactions, BigDecimal purchases, BigDecimal shareOfSales, BigDecimal paid, BigDecimal outstanding) {}
    public record PaymentSlice(String method, BigDecimal amount, BigDecimal percent) {}
    public record Payments(List<PaymentSlice> methods, BigDecimal received, BigDecimal notYetPaid) {}
    public record ExpenseCategory(String name, BigDecimal amount, BigDecimal percent) {}
    public record ExpenseRow(UUID id, String category, String description, BigDecimal amount, LocalDate date) {}
    public record Expenses(BigDecimal total, List<ExpenseCategory> categories, List<ExpenseRow> recent) {}
    public record StockItem(UUID variantId, String name, BigDecimal quantity, BigDecimal threshold) {}
    public record StockReceipt(String supplier, BigDecimal total, Instant at) {}
    public record Stock(long totalProducts, long lowStock, long outOfStock, List<StockItem> attention, List<StockReceipt> recentReceipts) {}
    /** invoicesOutstanding: sent, unpaid standalone invoices (a sale-backed invoice is already in customersOweYou). Overdue = past its due date. */
    public record Debts(BigDecimal customersOweYou, long customersOwing, Long oldestUnpaidCreditDays, BigDecimal youOweSuppliers, long suppliersOwed,
                        BigDecimal invoicesOutstanding, long invoicesOpen, BigDecimal invoicesOverdue, long invoicesOverdueCount) {}
    public record Transaction(String type, String title, String detail, BigDecimal amount, Instant at, String status, String link) {}
    public record Overview(DashboardService.DashboardDto current, boolean financial, Comparison comparison, Trend trend,
                           List<BestProduct> bestProducts, List<BestCustomer> bestCustomers, long customersServed, Payments payments,
                           Expenses expenses, Stock stock, Debts debts, boolean costDataComplete, List<Transaction> recent) {}

    @Transactional(readOnly = true)
    public Overview overview(String range, LocalDate from, LocalDate to) {
        DashboardService.DashboardDto cur = summaryService.summary(range, from, to);
        UUID bid = Tenant.businessId();
        boolean fin = access.current().contains(Permission.DASHBOARD_FINANCIAL);
        Business biz = businesses.findById(bid).orElseThrow();
        ZoneId zone = biz.zone();
        LocalDate today = LocalDate.now(zone);
        DateRange r = new DateRange(cur.from(), cur.to());
        Instant start = r.start(zone), end = r.endExclusive(zone);
        DashboardMath.Span prev = DashboardMath.previous(r.from(), r.to());
        DateRange pr = new DateRange(prev.from(), prev.to());
        Instant pStart = pr.start(zone), pEnd = pr.endExclusive(zone);

        // ---- previous period: same definitions, same queries ----
        SaleRepository.SalesAggregate pAgg = sales.aggregate(bid, SaleStatus.COMPLETED, pStart, pEnd);
        BigDecimal pSales = Money.scale(pAgg == null ? null : pAgg.getTotal());
        long pCount = pAgg == null || pAgg.getSaleCount() == null ? 0 : pAgg.getSaleCount();
        BigDecimal pCost = Money.scale(pAgg == null ? null : pAgg.getCost());
        BigDecimal pExpenses = fin ? Money.scale(expenses.sumBetween(bid, pr.from(), pr.to())) : null;
        boolean available = pCount > 0 || (pExpenses != null && pExpenses.signum() > 0);
        Comparison comparison;
        if (!available) {
            comparison = new Comparison(false, prev.from(), prev.to(), null, null, null, null);
        } else {
            Change cSales = new Change(pSales, DashboardMath.changePercent(cur.salesTotal(), pSales));
            Change cMoney = null, cGross = null, cExp = null;
            if (fin) {
                BigDecimal pRepaid = Money.scale(customerPayments.sumBetween(bid, pStart, pEnd));
                BigDecimal pReceived = Money.scale(salePayments.breakdown(bid, SaleStatus.COMPLETED, pStart, pEnd).stream()
                    .map(m -> m.getAmount() == null ? BigDecimal.ZERO : m.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add).add(pRepaid));
                BigDecimal pGross = pSales.subtract(pCost);
                cMoney = new Change(pReceived, DashboardMath.changePercent(cur.moneyReceived(), pReceived));
                cGross = new Change(pGross, DashboardMath.changePercent(cur.estimatedGrossProfit(), pGross));
                cExp = new Change(pExpenses, DashboardMath.changePercent(cur.expensesTotal(), pExpenses));
            }
            comparison = new Comparison(true, prev.from(), prev.to(), cSales, cMoney, cGross, cExp);
        }

        // ---- trend: current days with the previous period lined up day by day ----
        Map<LocalDate, BigDecimal> prevDays = new HashMap<>();
        sales.daily(bid, zone.getId(), pStart, pEnd).forEach(d -> prevDays.put(LocalDate.parse(d.getDay()), Money.scale(d.getTotal())));
        List<TrendPoint> daily = new ArrayList<>();
        List<LocalDate> dayList = new ArrayList<>();
        List<BigDecimal> salesList = new ArrayList<>();
        int i = 0;
        for (DashboardService.DayPoint d : cur.daily()) {
            LocalDate pd = prev.from().plusDays(i++);
            daily.add(new TrendPoint(d.date(), d.sales(), available ? prevDays.getOrDefault(pd, Money.scale(null)) : null));
            dayList.add(d.date());
            salesList.add(d.sales());
        }
        Trend trend = new Trend(daily, DashboardMath.weekly(dayList, salesList), DashboardMath.monthly(dayList, salesList));

        // ---- best products (by revenue) ----
        var top = saleItems.topByRevenue(bid, SaleStatus.COMPLETED, start, end, PageRequest.of(0, 10));
        Map<String, String> images = new HashMap<>();
        if (!top.isEmpty()) {
            for (Product p : products.findByBusinessIdAndNameIn(bid, top.stream().map(t -> t.getName()).collect(Collectors.toSet()))) {
                if (p.getImageUrl() != null) images.putIfAbsent(p.getName(), p.getImageUrl());
            }
        }
        List<BestProduct> bestProducts = top.stream().map(t -> new BestProduct(t.getName(), images.get(t.getName()),
            t.getQuantity() == null ? BigDecimal.ZERO : t.getQuantity(), Money.scale(t.getRevenue()),
            DashboardMath.share(Money.scale(t.getRevenue()), cur.salesTotal()))).toList();

        // ---- best customers (named customers only; walk-in sales are not ranked) ----
        var all = sales.bestCustomers(bid, SaleStatus.COMPLETED, start, end);
        Set<UUID> ids = all.stream().limit(10).map(c -> c.getCustomerId()).collect(Collectors.toSet());
        Map<UUID, BigDecimal> balances = new HashMap<>();
        if (fin && !ids.isEmpty()) for (Customer c : customers.findAllById(ids)) if (c.getBusinessId().equals(bid)) balances.put(c.getId(), Money.scale(c.getBalance()));
        List<BestCustomer> bestCustomers = all.stream().limit(10).map(c -> new BestCustomer(c.getCustomerId(), c.getName(),
            c.getTransactions() == null ? 0 : c.getTransactions(), Money.scale(c.getPurchases()),
            DashboardMath.share(Money.scale(c.getPurchases()), cur.salesTotal()),
            fin ? Money.scale(c.getPaid()) : null, fin ? balances.getOrDefault(c.getCustomerId(), Money.scale(null)) : null)).toList();

        // ---- stock ----
        List<LowStockDto> low = variants.lowStock(bid, ProductStatus.ACTIVE);
        List<StockItem> attention = low.stream().limit(6).map(l -> new StockItem(l.variantId(),
            l.variantLabel() == null || l.variantLabel().isBlank() ? l.productName() : l.productName() + " (" + l.variantLabel() + ")", l.quantity(), l.lowStockThreshold())).toList();
        List<StockReceipt> receipts = purchases.list(bid, PageRequest.of(0, 5)).getContent().stream()
            .map(p -> new StockReceipt(p.supplierName(), fin ? Money.scale(p.total()) : null, p.createdAt())).toList();
        long out = cur.stockHealth().outOfStock();
        Stock stock = new Stock(cur.totalProducts(), cur.lowStockCount(), out, attention, receipts);

        // ---- money sections (owner / manager only) ----
        Payments payments = null;
        Expenses exp = null;
        Debts debts = null;
        boolean costComplete = true;
        if (fin) {
            BigDecimal received = cur.moneyReceivedFromSales();
            payments = new Payments(cur.paymentBreakdown().stream().map(m -> new PaymentSlice(m.method(), m.amount(), DashboardMath.share(m.amount(), received))).toList(),
                received, Money.scale(sales.unpaidBetween(bid, SaleStatus.COMPLETED, start, end)));
            var cats = expenses.byCategory(bid, r.from(), r.to());
            List<ExpenseCategory> categories = cats.stream().limit(6).map(c -> new ExpenseCategory(c.getName(), Money.scale(c.getTotal()), DashboardMath.share(Money.scale(c.getTotal()), cur.expensesTotal()))).toList();
            List<ExpenseRow> recentExp = expenses.search(bid, r.from(), r.to(), PageRequest.of(0, 5)).getContent().stream()
                .map(e -> new ExpenseRow(e.getId(), e.getCategory(), e.getDescription(), Money.scale(e.getAmount()), e.getExpenseDate())).toList();
            exp = new Expenses(cur.expensesTotal(), categories, recentExp);
            Instant oldest = sales.oldestUnpaid(bid, SaleStatus.COMPLETED);
            var inv = invoiceReporting.open(bid, today);
            debts = new Debts(cur.customersOweYou(), customers.countOwing(bid), oldest == null ? null : Duration.between(oldest, Instant.now()).toDays(),
                cur.suppliersYouOwe(), suppliers.countOwed(bid), inv.outstanding(), inv.count(), inv.overdue(), inv.overdueCount());
            costComplete = saleItems.withoutCost(bid, SaleStatus.COMPLETED, start, end) == 0;
        }

        return new Overview(cur, fin, comparison, trend, bestProducts, bestCustomers, all.size(), payments, exp, stock, debts, costComplete, transactions(bid, zone, today, fin));
    }

    /** The latest sales, customer repayments, expenses and stock receipts together, newest first. */
    private List<Transaction> transactions(UUID bid, ZoneId zone, LocalDate today, boolean fin) {
        List<Transaction> out = new ArrayList<>();
        for (SaleSummary s : sales.search(bid, Instant.EPOCH, Instant.now().plusSeconds(86400), PageRequest.of(0, 8)).getContent()) {
            String status = s.status() == SaleStatus.REFUNDED ? "Refunded" : (s.amountOwed() != null && s.amountOwed().signum() > 0 ? "Owes " + Money.scale(s.amountOwed()).toPlainString() : "Paid");
            out.add(new Transaction("SALE", "Sale #" + s.saleNumber(), s.customerName() == null ? "Walk-in customer" : s.customerName(), Money.scale(s.total()), s.createdAt(), status, "/more/sales"));
        }
        if (fin) {
            List<CustomerPayment> pays = customerPayments.findTop8ByBusinessIdOrderByCreatedAtDesc(bid);
            Set<UUID> cids = pays.stream().map(CustomerPayment::getCustomerId).collect(Collectors.toSet());
            Map<UUID, String> names = new HashMap<>();
            if (!cids.isEmpty()) for (Customer c : customers.findAllById(cids)) if (c.getBusinessId().equals(bid)) names.put(c.getId(), c.getName());
            for (CustomerPayment p : pays) out.add(new Transaction("REPAYMENT", "Customer payment", names.getOrDefault(p.getCustomerId(), "Customer"), Money.scale(p.getAmount()), p.getCreatedAt(), "Received", "/customers/" + p.getCustomerId()));
            for (Expense e : expenses.search(bid, LocalDate.of(2000, 1, 1), today.plusDays(1), PageRequest.of(0, 8)).getContent()) {
                out.add(new Transaction("EXPENSE", e.getCategory(), e.getDescription() == null ? "" : e.getDescription(), Money.scale(e.getAmount()), e.getExpenseDate().atStartOfDay(zone).toInstant(), "Recorded", "/expenses"));
            }
        }
        for (PurchaseSummary p : purchases.list(bid, PageRequest.of(0, 8)).getContent()) {
            out.add(new Transaction("STOCK_RECEIPT", "Stock received", p.supplierName() == null ? "No supplier" : p.supplierName(), fin ? Money.scale(p.total()) : null, p.createdAt(),
                fin && p.amountOwed() != null && p.amountOwed().signum() > 0 ? "Owes " + Money.scale(p.amountOwed()).toPlainString() : "Received", "/suppliers"));
        }
        out.sort(Comparator.comparing(Transaction::at).reversed());
        return out.size() > 10 ? new ArrayList<>(out.subList(0, 10)) : out;
    }
}
