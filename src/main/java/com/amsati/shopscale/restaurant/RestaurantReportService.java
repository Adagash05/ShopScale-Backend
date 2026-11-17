package com.amsati.shopscale.restaurant;

import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.DateRange;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.industry.IndustryGuard;
import com.amsati.shopscale.industry.Workflow;
import com.amsati.shopscale.security.Tenant;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operational reports for menu businesses, read straight from sales, kitchen orders and stock movements (no copies of the data). */
@Service
@RequiredArgsConstructor
public class RestaurantReportService {
    private final JdbcTemplate jdbc;
    private final BusinessRepository businesses;
    private final IndustryGuard industry;

    public record ItemSales(String name, BigDecimal quantity, BigDecimal revenue, BigDecimal cost, BigDecimal profit) {}
    public record Day(String date, long orders, long cancelled) {}
    public record Volume(long total, long dineIn, long takeaway, long delivery, long cancelled, BigDecimal avgMinutesToReady, List<Day> byDay) {}
    public record Ingredient(String name, String unit, String reason, BigDecimal quantity, BigDecimal cost) {}
    public record Report(LocalDate from, LocalDate to, List<ItemSales> salesByItem, Volume orders, List<Ingredient> ingredientUse) {}

    @Transactional(readOnly = true)
    public Report report(String range, LocalDate from, LocalDate to) {
        UUID bid = Tenant.businessId();
        industry.requireAny(bid, Workflow.RESTAURANT, Workflow.PRODUCTION);
        Business b = businesses.findById(bid).orElseThrow(() -> ApiException.notFound("Business not found"));
        DateRange r = DateRange.resolve(range, from, to, LocalDate.now(b.zone()));
        Timestamp a = Timestamp.from(r.start(b.zone())), z = Timestamp.from(r.endExclusive(b.zone()));
        String zone = b.zone().getId();

        // Items sold, net of returns. Voided sales are left out.
        List<ItemSales> items = jdbc.query(
            "select si.product_name || coalesce(' (' || nullif(si.variant_label, '') || ')', '') as name, "
          + "sum(si.quantity - si.quantity_refunded) as qty, sum(si.unit_price * (si.quantity - si.quantity_refunded)) as revenue, "
          + "sum(si.unit_cost * (si.quantity - si.quantity_refunded)) as cost "
          + "from sale_items si join sales s on s.id = si.sale_id join product_variants v on v.id = si.variant_id join products p on p.id = v.product_id "
          + "where s.business_id = ? and s.status <> 'VOIDED' and s.created_at >= ? and s.created_at < ? and p.kind in ('MENU','FINISHED') "
          + "group by si.variant_id, si.product_name, si.variant_label having sum(si.quantity - si.quantity_refunded) > 0 order by revenue desc limit 200",
            (rs, i) -> {
                BigDecimal rev = Money.scale(rs.getBigDecimal("revenue")), cost = Money.scale(rs.getBigDecimal("cost"));
                return new ItemSales(rs.getString("name"), rs.getBigDecimal("qty"), rev, cost, rev.subtract(cost));
            }, bid, a, z);

        List<Day> days = jdbc.query(
            "select to_char(created_at at time zone ?, 'YYYY-MM-DD') as d, count(*) as n, count(*) filter (where status = 'CANCELLED') as c "
          + "from kitchen_orders where business_id = ? and created_at >= ? and created_at < ? group by 1 order by 1",
            (rs, i) -> new Day(rs.getString("d"), rs.getLong("n"), rs.getLong("c")), zone, bid, a, z);
        Volume volume = jdbc.queryForObject(
            "select count(*) as total, count(*) filter (where order_type = 'DINE_IN') as dine, count(*) filter (where order_type = 'TAKEAWAY') as take, "
          + "count(*) filter (where order_type = 'DELIVERY') as deliv, count(*) filter (where status = 'CANCELLED') as cancelled, "
          + "(select avg(extract(epoch from (i.ready_at - o2.created_at)) / 60.0) from kitchen_order_items i join kitchen_orders o2 on o2.id = i.order_id "
          + " where o2.business_id = ? and o2.created_at >= ? and o2.created_at < ? and i.ready_at is not null) as ready_min "
          + "from kitchen_orders where business_id = ? and created_at >= ? and created_at < ?",
            (rs, i) -> new Volume(rs.getLong("total"), rs.getLong("dine"), rs.getLong("take"), rs.getLong("deliv"), rs.getLong("cancelled"),
                rs.getBigDecimal("ready_min") == null ? null : rs.getBigDecimal("ready_min").setScale(1, java.math.RoundingMode.HALF_UP), days),
            bid, a, z, bid, a, z);

        // Ingredient use comes from real stock movements: sold dishes, production, wastage.
        List<Ingredient> use = jdbc.query(
            "select p.name || coalesce(' (' || nullif(v.label, '') || ')', '') as name, v.unit as unit, m.type as reason, "
          + "sum(-m.quantity_change) as qty, sum(-m.quantity_change * v.cost) as cost "
          + "from stock_movements m join product_variants v on v.id = m.variant_id join products p on p.id = v.product_id "
          + "where m.business_id = ? and p.kind = 'INGREDIENT' and m.quantity_change < 0 and m.type in ('SALE','WASTAGE','PRODUCTION_OUT') "
          + "and m.created_at >= ? and m.created_at < ? group by v.id, p.name, v.label, v.unit, m.type order by cost desc limit 300",
            (rs, i) -> new Ingredient(rs.getString("name"), rs.getString("unit"), rs.getString("reason"), rs.getBigDecimal("qty"), Money.scale(rs.getBigDecimal("cost"))),
            bid, a, z);
        return new Report(r.from(), r.to(), items, volume, use);
    }
}
