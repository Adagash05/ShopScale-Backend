package com.amsati.shopscale.production;

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
 * Simple production recording ("we baked 40 loaves today"). No work orders or schedules: one entry takes the ingredients out
 * of stock (when the item has a recipe) and puts the finished goods into stock, in one transaction. A retry with the same
 * request id records once.
 */
@Service
@RequiredArgsConstructor
public class ProductionService {
    private final JdbcTemplate jdbc;
    private final ProductVariantRepository variants;
    private final RecipeService recipes;
    private final StockService stock;
    private final UnitService units;
    private final BusinessRepository businesses;
    private final IndustryGuard industry;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    public record Request(@NotNull UUID variantId, @NotNull @DecimalMin(value = "0.001", message = "Enter how many were made") BigDecimal quantity,
                          @Size(max = 300) String note, UUID branchId, @Size(max = 64) String clientRequestId) {}
    public record Dto(UUID id, UUID variantId, String itemName, BigDecimal quantity, BigDecimal unitCost, BigDecimal totalCost, boolean usedRecipe, String note, Instant createdAt) {}

    @Transactional
    public Dto record(Request r) {
        UUID bid = Tenant.businessId();
        UUID uid = Tenant.userId();
        industry.requireAny(bid, Workflow.PRODUCTION, Workflow.RESTAURANT);
        subscriptions.requireFeature(bid, "PRODUCTION", "Production records");
        subscriptions.requireWritable(bid);
        String rid = r.clientRequestId() == null || r.clientRequestId().isBlank() ? null : r.clientRequestId();
        if (rid != null) {
            List<Dto> prior = jdbc.query(SELECT + " where p.business_id = ? and p.client_request_id = ?", MAP, bid, rid);
            if (!prior.isEmpty()) return prior.get(0);
        }
        List<ProductVariant> locked = variants.lockAll(bid, Set.of(r.variantId()));
        if (locked.isEmpty()) throw ApiException.badRequest("That product was not found");
        ProductVariant v = locked.get(0);
        if ("INGREDIENT".equals(v.getProduct().getKind())) throw ApiException.badRequest("Ingredients are bought, not produced. Choose a finished product.");
        units.requireValidQuantity(v, r.quantity());
        Business b = businesses.findById(bid).orElseThrow(() -> ApiException.notFound("Business not found"));
        UUID branch = stock.resolveBranch(bid, r.branchId());
        UUID id = UUID.randomUUID();
        String ref = "PROD-" + id.toString().substring(0, 8);
        String name = v.getProduct().getName() + (v.getLabel() == null || v.getLabel().isBlank() ? "" : " (" + v.getLabel() + ")");
        Recipe recipe = recipes.find(bid, v.getId()).orElse(null);
        if (recipe != null && "ON_SALE".equals(recipe.getMode())) throw ApiException.conflict(name + " is made fresh when it is ordered, so its ingredients are used at the sale. Only record production for items you make ahead.");
        BigDecimal unitCost = v.getCost();
        boolean used = false;
        BigDecimal before = stock.onHand(v, branch);
        if (recipe != null) {
            recipes.requireIngredients(recipe, r.quantity(), branch, b.isAllowNegativeStock());
            BigDecimal cost = recipes.consume(recipe, r.quantity(), branch, MovementType.PRODUCTION_OUT, ref, uid, b.isAllowNegativeStock());
            unitCost = cost.divide(r.quantity(), 2, RoundingMode.HALF_UP);
            used = true;
            // Average the cost of what is on the shelf with this batch, so selling it shows an honest profit.
            BigDecimal have = before.max(BigDecimal.ZERO);
            BigDecimal avg = have.multiply(v.getCost()).add(r.quantity().multiply(unitCost)).divide(have.add(r.quantity()), 2, RoundingMode.HALF_UP);
            v.setCost(avg);
        }
        stock.apply(v, branch, MovementType.PRODUCTION_IN, r.quantity(), "Produced", ref, uid, true);
        jdbc.update("insert into production_runs (id, business_id, branch_id, variant_id, item_name, quantity, unit_cost, used_recipe, note, client_request_id, created_by) values (?,?,?,?,?,?,?,?,?,?,?)",
            id, bid, branch, v.getId(), name, r.quantity(), Money.scale(unitCost), used, r.note() == null || r.note().isBlank() ? null : r.note().trim(), rid, uid);
        audit.record(bid, uid, "PRODUCTION_RECORDED", "production_run", id, name + " x" + r.quantity().stripTrailingZeros().toPlainString());
        return jdbc.query(SELECT + " where p.id = ?", MAP, id).get(0);
    }

    @Transactional(readOnly = true)
    public List<Dto> list(String range, LocalDate from, LocalDate to) {
        UUID bid = Tenant.businessId();
        Business b = businesses.findById(bid).orElseThrow(() -> ApiException.notFound("Business not found"));
        DateRange r = DateRange.resolve(range, from, to, LocalDate.now(b.zone()));
        return jdbc.query(SELECT + " where p.business_id = ? and p.created_at >= ? and p.created_at < ? order by p.created_at desc limit 500", MAP,
            bid, Timestamp.from(r.start(b.zone())), Timestamp.from(r.endExclusive(b.zone())));
    }

    private static final String SELECT = "select p.id, p.variant_id, p.item_name, p.quantity, p.unit_cost, p.used_recipe, p.note, p.created_at from production_runs p";
    private static final org.springframework.jdbc.core.RowMapper<Dto> MAP = (rs, i) -> {
        BigDecimal q = rs.getBigDecimal("quantity"), c = rs.getBigDecimal("unit_cost");
        return new Dto(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class), rs.getString("item_name"), q, c, Money.scale(q.multiply(c)),
            rs.getBoolean("used_recipe"), rs.getString("note"), rs.getTimestamp("created_at").toInstant());
    };
}
