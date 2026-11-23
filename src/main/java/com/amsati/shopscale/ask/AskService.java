package com.amsati.shopscale.ask;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.customer.Customer;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.dashboard.DashboardOverviewService;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ask ShopScale: a fixed set of business questions answered from this business's own records. It is not a chatbot and does not read free text.
 * Every figure comes from the same server-side aggregates as the dashboard (DashboardOverviewService), scoped to the signed-in business; the
 * browser sends only a question key and a period. Access needs the ASK_SHOPSCALE plan feature, checked here on the server.
 * Sales (revenue) is never mixed with money received or with profit, and unpaid credit is never counted as money received.
 */
@Service
@RequiredArgsConstructor
public class AskService {
    public static final String FEATURE = "ASK_SHOPSCALE";

    private final DashboardOverviewService overviews;
    private final CustomerRepository customers;
    private final SubscriptionService subscriptions;
    private final AccessService access;
    private final com.amsati.shopscale.invoice.InvoiceReporting invoiceReporting;

    public enum Q {
        SALES("What were my sales?", false, true),
        TOP_PRODUCTS_UNITS("Which products sold the most?", false, true),
        TOP_PRODUCTS_REVENUE("Which products brought in the most sales value?", false, true),
        MONEY_RECEIVED("How much money have I actually received?", true, true),
        CUSTOMERS_OWING("Which customers owe me money?", false, false),
        TOP_EXPENSES("Which expenses were highest?", true, true),
        COMPARE("How do my sales compare with the period before?", false, true),
        PAYMENT_METHODS("How do customers pay me?", true, true);

        public final String title;
        public final boolean financialOnly, usesPeriod;
        Q(String title, boolean financialOnly, boolean usesPeriod) { this.title = title; this.financialOnly = financialOnly; this.usesPeriod = usesPeriod; }
    }

    public record Link(String label, String to) {}
    public record Figure(String label, String type, BigDecimal value, String note) {}
    public record Row(String label, String detail, String type, BigDecimal value, String link) {}
    public record Question(String key, String title, boolean usesPeriod, boolean allowed) {}
    public record Answer(String key, String question, LocalDate from, LocalDate to, boolean available, String headline,
                         List<Figure> figures, List<Row> rows, List<String> notes, List<Link> links) {}

    private boolean financial() { return access.current().contains(Permission.DASHBOARD_FINANCIAL); }

    /** The questions this person can ask. The plan is checked here so a plan without the feature is refused, not just hidden. */
    @Transactional(readOnly = true)
    public List<Question> questions() {
        subscriptions.requireFeature(Tenant.businessId(), FEATURE, "Ask ShopScale");
        boolean fin = financial();
        List<Question> out = new ArrayList<>();
        for (Q q : Q.values()) out.add(new Question(q.name(), q.title, q.usesPeriod, !q.financialOnly || fin));
        return out;
    }

    @Transactional(readOnly = true)
    public Answer ask(String key, String range, LocalDate from, LocalDate to) {
        subscriptions.requireFeature(Tenant.businessId(), FEATURE, "Ask ShopScale");
        Q q;
        try { q = Q.valueOf(key == null ? "" : key.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw ApiException.badRequest("Choose one of the suggested questions"); }
        boolean fin = financial();
        if (q.financialOnly && !fin) {
            return new Answer(q.name(), q.title, null, null, false, "Only the owner and managers can see this.", List.of(), List.of(), List.of(), List.of());
        }
        if (q == Q.CUSTOMERS_OWING) return owing(q);
        DashboardOverviewService.Overview o = overviews.overview(range == null || range.isBlank() ? "thisMonth" : range, from, to);
        var cur = o.current();
        String when = AskText.period(cur.from(), cur.to());
        List<Figure> figs = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        String headline;
        boolean available = true;

        switch (q) {
            case SALES -> {
                headline = "You made " + AskText.naira(cur.salesTotal()) + " in sales (" + AskText.count(cur.salesCount(), "sale", "sales") + "), " + when + ".";
                figs.add(new Figure("Total sales", "money", cur.salesTotal(), "Completed sales after refunds. Credit sales count when they are made."));
                figs.add(new Figure("Number of sales", "number", BigDecimal.valueOf(cur.salesCount()), null));
                if (cur.salesCount() > 0) figs.add(new Figure("Average sale", "money", cur.salesTotal().divide(BigDecimal.valueOf(cur.salesCount()), 2, java.math.RoundingMode.HALF_UP), null));
                if (fin && o.costDataComplete()) figs.add(new Figure("Gross profit", "money", cur.estimatedGrossProfit(), "Sales minus what the products cost you. This is profit; the total above is not."));
                if (fin && !o.costDataComplete()) notes.add("Profit is not shown because some items sold have no cost price.");
                if (cur.salesCount() == 0) { available = false; headline = "No sales were recorded " + when + "."; }
                links.add(new Link("See sales history", "/more/sales"));
            }
            case TOP_PRODUCTS_UNITS -> {
                for (var p : cur.topProducts()) rows.add(new Row(p.name(), null, "number", p.quantity(), "/products?q=" + enc(p.name())));
                available = !rows.isEmpty();
                headline = available ? rows.get(0).label() + " sold the most (" + stripZeros(rows.get(0).value()) + " sold), " + when + "." : "Nothing was sold " + when + ".";
                notes.add("Ranked by number of items sold, not by money.");
                links.add(new Link("See products", "/products"));
            }
            case TOP_PRODUCTS_REVENUE -> {
                for (var p : o.bestProducts()) rows.add(new Row(p.name(), stripZeros(p.units()) + " sold" + (p.shareOfSales() == null ? "" : " · " + AskText.percent(p.shareOfSales()) + " of sales"), "money", p.revenue(), "/products?q=" + enc(p.name())));
                available = !rows.isEmpty();
                headline = available ? rows.get(0).label() + " brought in the most sales value (" + AskText.naira(rows.get(0).value()) + "), " + when + "." : "Nothing was sold " + when + ".";
                notes.add("Ranked by sales value (revenue), not by profit.");
                links.add(new Link("See products", "/products"));
            }
            case MONEY_RECEIVED -> {
                headline = "You received " + AskText.naira(cur.moneyReceived()) + ", " + when + ".";
                figs.add(new Figure("Money received", "money", cur.moneyReceived(), "Payments actually recorded in this period."));
                figs.add(new Figure("From sales", "money", cur.moneyReceivedFromSales(), "Cash, transfer and card payments on sales."));
                figs.add(new Figure("Customers paying what they owed", "money", cur.repaymentsReceived(), "Repayments on earlier credit."));
                if (o.payments() != null && o.payments().notYetPaid().signum() > 0)
                    figs.add(new Figure("Sold on credit, not yet paid", "money", o.payments().notYetPaid(), "Not counted as money received."));
                if (cur.moneyReceived().signum() == 0) { available = false; headline = "No payments were recorded " + when + "."; }
                links.add(new Link("See sales", "/more/sales"));
            }
            case TOP_EXPENSES -> {
                var ex = o.expenses();
                for (var c : ex.categories()) rows.add(new Row(c.name(), AskText.percent(c.percent()) + " of expenses", "money", c.amount(), null));
                available = !rows.isEmpty();
                headline = available ? "Your biggest expense was " + rows.get(0).label() + " (" + AskText.naira(rows.get(0).value()) + "); total expenses " + AskText.naira(ex.total()) + ", " + when + "." : "No expenses were recorded " + when + ".";
                figs.add(new Figure("Total expenses", "money", ex.total(), null));
                notes.add("Grouped by expense category.");
                links.add(new Link("See expenses", "/expenses"));
            }
            case COMPARE -> {
                var c = o.comparison();
                if (c == null || !c.available()) {
                    available = false;
                    headline = "There is not enough sales history before " + when + " to compare.";
                } else {
                    String ch = AskText.change(c.sales().changePercent());
                    headline = "Sales were " + AskText.naira(cur.salesTotal()) + " " + when + ", compared with " + AskText.naira(c.sales().previous()) + " the period before (" + AskText.period(c.from(), c.to()) + ")"
                        + (ch == null ? "." : ", " + ch + ".");
                    figs.add(new Figure("Sales now", "money", cur.salesTotal(), when));
                    figs.add(new Figure("Sales before", "money", c.sales().previous(), AskText.period(c.from(), c.to())));
                    if (c.sales().changePercent() != null) figs.add(new Figure("Change", "percent", c.sales().changePercent(), null));
                    if (fin && c.moneyReceived() != null) figs.add(new Figure("Money received before", "money", c.moneyReceived().previous(), null));
                    if (fin && c.expenses() != null) figs.add(new Figure("Expenses before", "money", c.expenses().previous(), null));
                    notes.add("The earlier period is the same length, ending the day before this one starts.");
                }
                links.add(new Link("Open the dashboard", "/dashboard"));
            }
            case PAYMENT_METHODS -> {
                var pm = o.payments();
                for (var m : pm.methods()) rows.add(new Row(methodName(m.method()), AskText.percent(m.percent()) + " of money received", "money", m.amount(), null));
                rows.sort(Comparator.comparing(Row::value).reversed());
                available = !rows.isEmpty();
                headline = available ? "Most of your money came by " + rows.get(0).label().toLowerCase() + " (" + AskText.naira(rows.get(0).value()) + " of " + AskText.naira(pm.received()) + "), " + when + "." : "No sale payments were recorded " + when + ".";
                if (pm.notYetPaid().signum() > 0) notes.add(AskText.naira(pm.notYetPaid()) + " was sold on credit and is not yet paid. It is not counted as money received.");
                notes.add("Based on payments recorded, by amount. Customer repayments are not included.");
                links.add(new Link("See sales", "/more/sales"));
            }
            default -> throw ApiException.badRequest("Choose one of the suggested questions");
        }
        return new Answer(q.name(), q.title, cur.from(), cur.to(), available, headline, figs, rows, notes, links);
    }

    private Answer owing(Q q) {
        var bid = Tenant.businessId();
        List<Customer> list = customers.owing(bid);
        List<Row> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Customer c : list) total = total.add(c.getBalance());
        for (Customer c : list.stream().limit(10).toList()) rows.add(new Row(c.getName(), null, "money", Money.scale(c.getBalance()), "/customers/" + c.getId()));
        List<Figure> figs = new ArrayList<>();
        figs.add(new Figure("Total owed to you", "money", Money.scale(total), "As of today."));
        figs.add(new Figure("Customers owing", "number", BigDecimal.valueOf(list.size()), null));
        String headline = list.isEmpty() ? "No customer owes you anything right now."
            : AskText.count(list.size(), "customer owes", "customers owe") + " you " + AskText.naira(total) + " in total.";
        var inv = invoiceReporting.open(bid, LocalDate.now(java.time.ZoneId.of("Africa/Lagos")));
        if (inv.count() > 0) {
            figs.add(new Figure("Unpaid invoices", "money", Money.scale(inv.outstanding()), AskText.count(inv.count(), "invoice", "invoices") + " sent and not fully paid. Separate from the customer balances above."));
            if (inv.overdueCount() > 0) figs.add(new Figure("Overdue invoices", "money", Money.scale(inv.overdue()), AskText.count(inv.overdueCount(), "invoice is", "invoices are") + " past the due date."));
        }
        List<String> notes = new ArrayList<>();
        notes.add("This is what is owed today, whatever period you pick. Biggest balances first" + (list.size() > 10 ? " (top 10 shown)." : "."));
        return new Answer(q.name(), q.title, null, null, !list.isEmpty() || inv.count() > 0, headline, figs, rows, notes, List.of(new Link("See customers who owe you", "/customers?owing=1") ));
    }

    private static String methodName(String m) {
        return switch (m == null ? "" : m) { case "CASH" -> "Cash"; case "TRANSFER" -> "Bank transfer"; case "CARD" -> "Card"; default -> m; };
    }
    private static String stripZeros(BigDecimal v) { return v == null ? "0" : v.stripTrailingZeros().toPlainString(); }
    private static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }
}
