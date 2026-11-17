package com.amsati.shopscale.wastage;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.DateRange;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.industry.IndustryGuard;
import com.amsati.shopscale.industry.Workflow;
import com.amsati.shopscale.inventory.MovementType;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.recipe.Recipe;
import com.amsati.shopscale.recipe.RecipeService;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import com.amsati.shopscale.unit.UnitService;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wastage: food or ingredients that were thrown away, spoiled or lost. Stock leaves through the normal inventory path
 * (a made-to-order dish uses up its ingredients), every record has a reason, and a retry with the same request id is
 * recorded once.
 */
@Service
@RequiredArgsConstructor
public class WastageService {
    public static final Set<String> REASONS = Set.of("SPOILAGE", "EXPIRED", "DAMAGE", "PREP_WASTE", "THEFT", "OTHER");

    private final JdbcTemplate jdbc;
    private final ProductVariantRepository variants;
    private final RecipeService recipes;
    private final StockService stock;
    private final UnitService units;
    private final BusinessRepository businesses;
    private final IndustryGuard industry;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    public record Request(@NotNull UUID variantId, @NotNull @DecimalMin(value = "0.001", message = "Enter a quantity above zero") BigDecimal quantity,
                          @NotNull String reason, @Size(max = 300) String note, UUID branchId, @Size(max = 64) String clientRequestId) {}
    public record Dto(UUID id, UUID variantId, String itemName, BigDecimal quantity, BigDecimal unitCost, BigDecimal value, String reason, String note, Instant createdAt) {}
    public record ListDto(List<Dto> items, BigDecimal totalValue, Map<String, BigDecimal> valueByReason) {}

    @Transactional
    public Dto record(Request r) {
        UUID bid = Tenant.businessId();
        industry.requireAny(bid, Workflow.RESTAURANT, Workflow.PRODUCTION, Workflow.CATERING);
        subscriptions.requireFeature(bid, "WASTAGE", "Wastage records");
        subscriptions.requireWritable(bid);
        return recordInternal(bid, Tenant.userId(), r.variantId(), r.quantity(), r.reason(), r.note(), r.branchId(), r.clientRequestId(), null);
    }

    /** Also used by the kitchen (a cancelled dish that was already being cooked) and by catering. */
    @Transactional
    public Dto recordInternal(UUID bid, UUID userId, UUID variantId, BigDecimal quantity, String reason, String note, UUID branchId, String requestId, String reference) {
        if (!REASONS.contains(reason)) throw ApiException.badRequest("Choose why this was wasted");
        String rid = requestId == null || requestId.isBlank() ? null : requestId;
        if (rid != null) {
            List<Dto> prior = jdbc.query(SELECT + " where w.business_id = ? and w.client_request_id = ?", MAP, bid, rid);
            if (!prior.isEmpty()) return prior.get(0);
        }
        List<ProductVariant> locked = variants.lockAll(bid, Set.of(variantId));
        if (locked.isEmpty()) throw ApiException.badRequest("That item was not found");
        ProductVariant v = locked.get(0);
        units.requireValidQuantity(v, quantity);
        Business b = businesses.findById(bid).orElseThrow(() -> ApiException.notFound("Business not found"));
        UUID branch = stock.resolveBranch(bid, branchId);
        String name = v.getProduct().getName() + (v.getLabel() == null || v.getLabel().isBlank() ? "" : " (" + v.getLabel() + ")");
        UUID id = UUID.randomUUID();
        String ref = reference == null ? "WASTE-" + id.toString().substring(0, 8) : reference;
        Recipe made = recipes.madeToOrder(bid, Set.of(v.getId())).get(v.getId());
        BigDecimal unitCost;
        if (made != null) {
            BigDecimal cost = recipes.consume(made, quantity, branch, MovementType.WASTAGE, ref, userId, b.isAllowNegativeStock());
            unitCost = cost.divide(quantity, 2, RoundingMode.HALF_UP);
        } else {
            stock.apply(v, branch, MovementType.WASTAGE, quantity.negate(), reason.toLowerCase().replace('_', ' ') + (note == null || note.isBlank() ? "" : ": " + note.trim()), ref, userId, b.isAllowNegativeStock());
            unitCost = v.getCost();
        }
        jdbc.update("insert into wastage_records (id, business_id, branch_id, variant_id, item_name, quantity, unit_cost, reason, note, created_by, client_request_id, reference) values (?,?,?,?,?,?,?,?,?,?,?,?)",
            id, bid, branch, v.getId(), name, quantity, Money.scale(unitCost), reason, note == null || note.isBlank() ? null : note.trim(), userId, rid, ref);
        audit.record(bid, userId, "WASTAGE_RECORDED", "wastage", id, name + " x" + quantity.stripTrailingZeros().toPlainString() + " (" + reason + ")");
        return jdbc.query(SELECT + " where w.id = ?", MAP, id).get(0);
    }

    @Transactional(readOnly = true)
    public ListDto list(String range, LocalDate from, LocalDate to) {
        UUID bid = Tenant.businessId();
        Business b = businesses.findById(bid).orElseThrow(() -> ApiException.notFound("Business not found"));
        DateRange r = DateRange.resolve(range, from, to, LocalDate.now(b.zone()));
        Timestamp a = Timestamp.from(r.start(b.zone())), z = Timestamp.from(r.endExclusive(b.zone()));
        List<Dto> items = jdbc.query(SELECT + " where w.business_id = ? and w.created_at >= ? and w.created_at < ? order by w.created_at desc limit 500", MAP, bid, a, z);
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> by = new LinkedHashMap<>();
        for (Dto d : items) { total = total.add(d.value()); by.merge(d.reason(), d.value(), BigDecimal::add); }
        return new ListDto(items, Money.scale(total), by);
    }

    private static final String SELECT = "select w.id, w.variant_id, w.item_name, w.quantity, w.unit_cost, w.reason, w.note, w.created_at from wastage_records w";
    private static final org.springframework.jdbc.core.RowMapper<Dto> MAP = (rs, i) -> {
        BigDecimal q = rs.getBigDecimal("quantity"), c = rs.getBigDecimal("unit_cost");
        return new Dto(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class), rs.getString("item_name"), q, c,
            Money.scale(q.multiply(c)), rs.getString("reason"), rs.getString("note"), rs.getTimestamp("created_at").toInstant());
    };
}
