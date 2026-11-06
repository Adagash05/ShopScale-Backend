import com.amsati.shopscale.insight.InsightRules;
import com.amsati.shopscale.purchasing.CostMath;
import com.amsati.shopscale.security.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

public class PureLogicHarness {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + name); } }
    static BigDecimal d(String s) { return new BigDecimal(s); }
    public static void main(String[] a) {
        // weighted average cost
        check("wavg 10@100 + 10@200 = 150.00", CostMath.weightedAverage(10, d("100"), 10, d("200")).equals(d("150.00")));
        check("wavg empty shelf takes new cost", CostMath.weightedAverage(0, d("100"), 5, d("120.5")).equals(d("120.50")));
        check("wavg negative stock takes new cost", CostMath.weightedAverage(-3, d("100"), 5, d("90")).equals(d("90.00")));
        check("wavg rounds half up", CostMath.weightedAverage(1, d("100"), 2, d("100.01")).equals(d("100.01")));
        check("wavg 3@10 + 1@11 = 10.25", CostMath.weightedAverage(3, d("10"), 1, d("11")).equals(d("10.25")));
        boolean threw = false; try { CostMath.weightedAverage(1, d("1"), 0, d("1")); } catch (IllegalArgumentException e) { threw = true; }
        check("wavg rejects zero received", threw);
        // stock cover
        check("cover: 6 sold in 30d, 2 left -> 10 days", Math.abs(InsightRules.daysOfCover(2, 6, 30) - 10.0) < 1e-9);
        check("cover: too few sales -> no rate", InsightRules.daysOfCover(2, 2, 30) == null);
        check("running out: 1 left, 30 sold in 30d (1/day)", InsightRules.runningOutSoon(1, 30, 30));
        check("not running out: 100 left", !InsightRules.runningOutSoon(100, 30, 30));
        check("already out of stock is handled elsewhere", !InsightRules.runningOutSoon(0, 30, 30));
        check("reorder 14 days at 1/day with 4 left = 10", InsightRules.suggestedReorder(4, 30, 30) == 10);
        check("reorder never negative", InsightRules.suggestedReorder(500, 30, 30) == 0);
        check("reorder with no sales = 0", InsightRules.suggestedReorder(0, 0, 30) == 0);
        // margins
        check("margin 100->90 = 10.0", InsightRules.marginPercent(d("100"), d("90")).equals(d("10.0")));
        check("margin 100->95 weak (5%)", InsightRules.weakMargin(d("100"), d("95")));
        check("margin 100->90 not weak (10%)", !InsightRules.weakMargin(d("100"), d("90")));
        check("selling below cost is weak", InsightRules.weakMargin(d("100"), d("120")));
        check("missing cost is not a margin", InsightRules.marginPercent(d("100"), d("0")) == null);
        // change
        check("expenses 100->130 = +30.0", InsightRules.changePercent(d("100"), d("130")) == 30.0);
        check("sales 200->150 = -25.0", InsightRules.changePercent(d("200"), d("150")) == -25.0);
        check("no previous -> null", InsightRules.changePercent(d("0"), d("50")) == null);
        // overdue
        Instant now = Instant.parse("2026-06-30T00:00:00Z");
        check("overdue after 15 days", InsightRules.isOverdue(now.minus(Duration.ofDays(15)), now));
        check("not overdue at 14 days", !InsightRules.isOverdue(now.minus(Duration.ofDays(14)), now));
        check("no activity is not flagged here", !InsightRules.isOverdue(null, now));
        // roles (pure)
        check("owner has everything", DefaultRoles.permissions(Role.OWNER).equals(EnumSet.allOf(Permission.class)));
        Set<Permission> admin = DefaultRoles.permissions(Role.ADMINISTRATOR);
        check("admin cannot manage billing", !admin.contains(Permission.SUBSCRIPTION_MANAGE) && !admin.contains(Permission.BILLING_MANAGE) && !admin.contains(Permission.STORE_PAYMENTS_MANAGE));
        Set<Permission> cashier = DefaultRoles.permissions(Role.SALES_STAFF);
        check("cashier sells", cashier.contains(Permission.SALES_CREATE));
        check("cashier has no money access", !cashier.contains(Permission.DASHBOARD_FINANCIAL) && !cashier.contains(Permission.EXPENSES_VIEW) && !cashier.contains(Permission.SALES_VIEW) && !cashier.contains(Permission.SALES_REFUND));
        check("kitchen only kitchen", DefaultRoles.permissions(Role.KITCHEN_STAFF).equals(EnumSet.of(Permission.KITCHEN_VIEW, Permission.KITCHEN_STATUS_UPDATE, Permission.KITCHEN_MARK_READY)));
        check("no escalation: manager cannot grant accountant", !DefaultRoles.canGrant(DefaultRoles.permissions(Role.MANAGER), DefaultRoles.permissions(Role.ACCOUNTANT)));
        check("escalation guard: owner can grant admin", DefaultRoles.canGrant(DefaultRoles.permissions(Role.OWNER), admin));
        check("custom has no built-ins", DefaultRoles.permissions(Role.CUSTOM).isEmpty());
        for (Permission p : Permission.values()) check("catalogue " + p, !p.module().isBlank() && !p.label().isBlank());
        System.out.println(pass + " checks passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
