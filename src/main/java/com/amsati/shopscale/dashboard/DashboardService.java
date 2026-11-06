package com.amsati.shopscale.dashboard;

import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.ProductStatus;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.DateRange;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.customer.CustomerPaymentRepository;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.expense.ExpenseRepository;
import com.amsati.shopscale.sales.SaleItemRepository;
import com.amsati.shopscale.sales.SalePaymentRepository;
import com.amsati.shopscale.sales.SaleRepository;
import com.amsati.shopscale.sales.SaleStatus;
import com.amsati.shopscale.sales.SaleSummary;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Financial rules (also documented in docs/FINANCE.md):
 *  sales            = total of COMPLETED sales in range (refunded sales excluded)
 *  gross profit     = sales - cost of products sold (cost is the snapshot taken at sale time)
 *  operating profit = gross profit - recorded expenses
 *  money received   = payments taken on COMPLETED sales + repayments from customers in range
 *  customers owe    = sum of positive customer balances right now (credit sales not yet repaid)
 * Credit sales count as sales when made; the cash arrives later as repayments.
 * People without DASHBOARD_FINANCIAL never receive profit, expense, cost, payment or balance figures.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {
    private final BusinessRepository businesses;
    private final SaleRepository sales;
    private final SalePaymentRepository salePayments;
    private final SaleItemRepository saleItems;
    private final CustomerPaymentRepository customerPayments;
    private final CustomerRepository customers;
    private final ExpenseRepository expenses;
    private final ProductVariantRepository variants;
    private final AccessService access;

    public record MethodTotal(String method, BigDecimal amount) {}
    public record TopProduct(String name, long quantity, BigDecimal revenue) {}
    public record CategoryTotal(String name, BigDecimal revenue) {}
    public record SlowItem(String name, int quantity) {}
    public record DayPoint(LocalDate date, BigDecimal sales, BigDecimal expenses, BigDecimal grossProfit) {}
    public record StockHealth(long outOfStock, long low, long healthy) {}
    public record Headline(BigDecimal today, BigDecimal last7Days, BigDecimal thisMonth) {}

    public record DashboardDto(LocalDate from, LocalDate to, boolean financial,
                               BigDecimal salesTotal, long salesCount, BigDecimal discountsGiven,
                               BigDecimal costOfProductsSold, BigDecimal estimatedGrossProfit,
                               BigDecimal expensesTotal, BigDecimal estimatedOperatingProfit,
                               BigDecimal moneyReceived, BigDecimal moneyReceivedFromSales, BigDecimal repaymentsReceived,
                               BigDecimal customersOweYou, BigDecimal stockValue, long lowStockCount,
                               Headline headline, List<DayPoint> daily, StockHealth stockHealth,
                               List<MethodTotal> paymentBreakdown, List<TopProduct> topProducts,
                               List<CategoryTotal> salesByCategory, List<SlowItem> slowMoving,
                               List<SaleSummary> recentSales) {}

    private BigDecimal salesBetween(UUID bid, LocalDate from, LocalDate to, ZoneId zone) {
        DateRange r = new DateRange(from, to);
        SaleRepository.SalesAggregate a = sales.aggregate(bid, SaleStatus.COMPLETED, r.start(zone), r.endExclusive(zone));
        return Money.scale(a == null ? null : a.getTotal());
    }

    @Transactional(readOnly = true)
    public DashboardDto summary(String range, LocalDate from, LocalDate to) {
        UUID bid = Tenant.businessId();
        boolean fin = access.current().contains(Permission.DASHBOARD_FINANCIAL);
        Business b = businesses.findById(bid).orElseThrow();
        ZoneId zone = b.zone();
        LocalDate today = LocalDate.now(zone);
        DateRange r = DateRange.resolve(range, from, to, today);
        Instant start = r.start(zone);
        Instant end = r.endExclusive(zone);

        SaleRepository.SalesAggregate agg = sales.aggregate(bid, SaleStatus.COMPLETED, start, end);
        BigDecimal salesTotal = Money.scale(agg == null ? null : agg.getTotal());
        BigDecimal cost = Money.scale(agg == null ? null : agg.getCost());
        BigDecimal discounts = Money.scale(agg == null ? null : agg.getDiscount());
        long count = agg == null || agg.getSaleCount() == null ? 0 : agg.getSaleCount();
        BigDecimal gross = salesTotal.subtract(cost);
        BigDecimal expensesTotal = Money.scale(expenses.sumBetween(bid, r.from(), r.to()));

        // Daily series, with empty days filled in so charts have a continuous axis.
        Map<LocalDate, SaleRepository.DayAggregate> saleDays = new HashMap<>();
        sales.daily(bid, zone.getId(), start, end).forEach(d -> saleDays.put(LocalDate.parse(d.getDay()), d));
        Map<LocalDate, BigDecimal> expenseDays = new HashMap<>();
        expenses.daily(bid, r.from(), r.to()).forEach(d -> expenseDays.put(d.getDay(), Money.scale(d.getTotal())));
        List<DayPoint> daily = new ArrayList<>();
        for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
            SaleRepository.DayAggregate sd = saleDays.get(d);
            BigDecimal s = Money.scale(sd == null ? null : sd.getTotal());
            BigDecimal c = Money.scale(sd == null ? null : sd.getCost());
            daily.add(new DayPoint(d, s, fin ? expenseDays.getOrDefault(d, Money.scale(null)) : null, fin ? s.subtract(c) : null));
        }

        List<TopProduct> top = saleItems.topProducts(bid, SaleStatus.COMPLETED, start, end, PageRequest.of(0, 5)).stream()
            .map(t -> new TopProduct(t.getName(), t.getQuantity() == null ? 0 : t.getQuantity(), Money.scale(t.getRevenue()))).toList();
        List<CategoryTotal> byCategory = saleItems.byCategory(bid, SaleStatus.COMPLETED, start, end).stream()
            .map(c -> new CategoryTotal(c.getName() == null ? "No category" : c.getName(), Money.scale(c.getRevenue()))).toList();
        List<SlowItem> slow = variants.slowMoving(bid, ProductStatus.ACTIVE, SaleStatus.COMPLETED, start, end, PageRequest.of(0, 5)).stream()
            .map(s -> new SlowItem(s.getLabel() == null ? s.getName() : s.getName() + " (" + s.getLabel() + ")", s.getQuantity() == null ? 0 : s.getQuantity())).toList();
        ProductVariantRepositoryHealth health = ProductVariantRepositoryHealth.of(variants.stockHealth(bid, ProductStatus.ACTIVE));
        Headline headline = new Headline(salesBetween(bid, today, today, zone), salesBetween(bid, today.minusDays(6), today, zone),
            salesBetween(bid, today.withDayOfMonth(1), today, zone));
        List<SaleSummary> recent = sales.search(bid, start, end, PageRequest.of(0, 5)).getContent();

        if (!fin) {
            return new DashboardDto(r.from(), r.to(), false, salesTotal, count, null, null, null, null, null, null, null, null,
                null, null, variants.lowStockCount(bid, ProductStatus.ACTIVE), headline, daily,
                new StockHealth(health.out, health.low, health.healthy), List.of(), top, byCategory, slow, recent);
        }

        List<MethodTotal> methods = salePayments.breakdown(bid, SaleStatus.COMPLETED, start, end).stream()
            .map(m -> new MethodTotal(m.getMethod().name(), Money.scale(m.getAmount()))).toList();
        BigDecimal fromSales = methods.stream().map(MethodTotal::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal repayments = Money.scale(customerPayments.sumBetween(bid, start, end));

        return new DashboardDto(r.from(), r.to(), true, salesTotal, count, discounts, cost, gross, expensesTotal,
            gross.subtract(expensesTotal), Money.scale(fromSales.add(repayments)), Money.scale(fromSales), repayments,
            Money.scale(customers.outstanding(bid)), Money.scale(variants.inventoryValue(bid, ProductStatus.ACTIVE)),
            variants.lowStockCount(bid, ProductStatus.ACTIVE), headline, daily,
            new StockHealth(health.out, health.low, health.healthy), methods, top, byCategory, slow, recent);
    }

    /** Null-safe holder for the stock health aggregate (a business with no items yields nulls). */
    private record ProductVariantRepositoryHealth(long out, long low, long healthy) {
        static ProductVariantRepositoryHealth of(ProductVariantRepository.StockHealth h) {
            if (h == null) return new ProductVariantRepositoryHealth(0, 0, 0);
            return new ProductVariantRepositoryHealth(h.getOutOfStock() == null ? 0 : h.getOutOfStock(),
                h.getLow() == null ? 0 : h.getLow(), h.getHealthy() == null ? 0 : h.getHealthy());
        }
    }
}
