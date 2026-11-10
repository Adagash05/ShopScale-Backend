package com.amsati.shopscale.recipe;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.industry.IndustryGuard;
import com.amsati.shopscale.industry.Workflow;
import com.amsati.shopscale.inventory.MovementType;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recipes: what a menu item or finished good is made from, and what that costs. Stock is only moved through StockService. */
@Service
@RequiredArgsConstructor
public class RecipeService {
    private final RecipeRepository recipes;
    private final RecipeLineRepository lines;
    private final ProductVariantRepository variants;
    private final StockService stock;
    private final IndustryGuard industry;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    public record LineRequest(@NotNull UUID ingredientVariantId, @NotNull @DecimalMin(value = "0.001", message = "Quantity must be above zero") BigDecimal quantity) {}
    public record RecipeRequest(@NotNull @DecimalMin(value = "0.001", message = "Yield must be above zero") BigDecimal yieldQuantity,
                                @NotNull String mode, String notes, @NotEmpty @Valid List<LineRequest> lines) {}
    public record LineDto(UUID ingredientVariantId, String name, String unit, BigDecimal quantity, BigDecimal unitCost, BigDecimal lineCost, BigDecimal onHand) {}
    public record RecipeDto(UUID variantId, BigDecimal yieldQuantity, String mode, String notes, BigDecimal costPerUnit, List<LineDto> lines) {}

    private void gate(UUID bid) {
        industry.requireAny(bid, Workflow.RESTAURANT, Workflow.PRODUCTION, Workflow.CATERING);
        subscriptions.requireFeature(bid, "RECIPES", "Recipes");
    }

    private static String nameOf(ProductVariant v) {
        return v.getProduct().getName() + (v.getLabel() == null || v.getLabel().isBlank() ? "" : " (" + v.getLabel() + ")");
    }

    @Transactional(readOnly = true)
    public RecipeDto get(UUID variantId) {
        UUID bid = Tenant.businessId();
        Recipe r = recipes.findByBusinessIdAndVariantId(bid, variantId).orElse(null);
        return r == null ? null : dto(r);
    }

    private RecipeDto dto(Recipe r) {
        List<RecipeLine> ls = lines.findByRecipeId(r.getId());
        Map<UUID, ProductVariant> vs = variants.findAllById(ls.stream().map(RecipeLine::getIngredientVariantId).toList()).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        List<LineDto> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (RecipeLine l : ls) {
            ProductVariant v = vs.get(l.getIngredientVariantId());
            BigDecimal lc = v.getCost().multiply(l.getQuantity());
            total = total.add(lc);
            out.add(new LineDto(v.getId(), nameOf(v), v.getUnit(), l.getQuantity(), v.getCost(), Money.scale(lc), v.getQuantity()));
        }
        BigDecimal per = total.divide(r.getYieldQuantity(), 2, RoundingMode.HALF_UP);
        return new RecipeDto(r.getVariantId(), r.getYieldQuantity(), r.getMode(), r.getNotes(), per, out);
    }

    @Transactional
    public RecipeDto save(UUID variantId, RecipeRequest req) {
        UUID bid = Tenant.businessId();
        gate(bid);
        if (!Set.of("ON_SALE", "PRODUCTION").contains(req.mode())) throw ApiException.badRequest("Choose how this item is made");
        ProductVariant out = variants.findById(variantId).filter(v -> v.getBusinessId().equals(bid)).orElseThrow(() -> ApiException.notFound("Item not found"));
        Set<UUID> seen = new HashSet<>();
        for (LineRequest l : req.lines()) {
            if (l.ingredientVariantId().equals(variantId)) throw ApiException.badRequest("An item cannot be an ingredient of itself");
            if (!seen.add(l.ingredientVariantId())) throw ApiException.badRequest("Each ingredient can be listed once");
        }
        Map<UUID, ProductVariant> ing = variants.findAllById(seen).stream().filter(v -> v.getBusinessId().equals(bid)).collect(Collectors.toMap(ProductVariant::getId, v -> v));
        if (ing.size() != seen.size()) throw ApiException.badRequest("One of the ingredients was not found");
        for (ProductVariant v : ing.values()) {
            if (recipes.findByBusinessIdAndVariantId(bid, v.getId()).filter(x -> lines.findByRecipeId(x.getId()).stream().anyMatch(y -> y.getIngredientVariantId().equals(variantId))).isPresent()) {
                throw ApiException.badRequest(nameOf(v) + " is itself made from this item. Recipes cannot loop.");
            }
        }
        Recipe r = recipes.findByBusinessIdAndVariantId(bid, variantId).orElseGet(() -> { Recipe n = new Recipe(); n.setBusinessId(bid); n.setVariantId(variantId); return n; });
        r.setYieldQuantity(req.yieldQuantity());
        r.setMode(req.mode());
        r.setNotes(req.notes());
        r.setUpdatedAt(java.time.Instant.now());
        recipes.save(r);
        lines.deleteByRecipe(r.getId());
        lines.flush();
        for (LineRequest l : req.lines()) {
            RecipeLine rl = new RecipeLine();
            rl.setRecipeId(r.getId());
            rl.setIngredientVariantId(l.ingredientVariantId());
            rl.setQuantity(l.quantity());
            lines.save(rl);
        }
        audit.record(bid, Tenant.userId(), "RECIPE_SAVED", "variant", out.getId(), nameOf(out));
        return dto(r);
    }

    @Transactional
    public void delete(UUID variantId) {
        UUID bid = Tenant.businessId();
        gate(bid);
        recipes.findByBusinessIdAndVariantId(bid, variantId).ifPresent(r -> {
            recipes.delete(r);
            audit.record(bid, Tenant.userId(), "RECIPE_DELETED", "variant", variantId, null);
        });
    }

    /** Variants in the list that are made to order (menu items). Their own stock is not used; their ingredients are. */
    @Transactional(readOnly = true)
    public Map<UUID, Recipe> madeToOrder(UUID bid, Collection<UUID> variantIds) {
        if (variantIds.isEmpty()) return Map.of();
        return recipes.findByBusinessIdAndVariantIdInAndMode(bid, variantIds, "ON_SALE").stream().collect(Collectors.toMap(Recipe::getVariantId, r -> r));
    }

    /** What one unit costs to make, from current ingredient costs. */
    @Transactional(readOnly = true)
    public BigDecimal unitCost(Recipe r) {
        List<RecipeLine> ls = lines.findByRecipeId(r.getId());
        Map<UUID, ProductVariant> vs = variants.findAllById(ls.stream().map(RecipeLine::getIngredientVariantId).toList()).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        BigDecimal total = BigDecimal.ZERO;
        for (RecipeLine l : ls) total = total.add(vs.get(l.getIngredientVariantId()).getCost().multiply(l.getQuantity()));
        return total.divide(r.getYieldQuantity(), 2, RoundingMode.HALF_UP);
    }

    /** Ingredient quantities needed to make {@code quantity} units. */
    public Map<UUID, BigDecimal> requirements(Recipe r, BigDecimal quantity) {
        Map<UUID, BigDecimal> need = new LinkedHashMap<>();
        BigDecimal scale = quantity.divide(r.getYieldQuantity(), 6, RoundingMode.HALF_UP);
        for (RecipeLine l : lines.findByRecipeId(r.getId())) need.merge(l.getIngredientVariantId(), l.getQuantity().multiply(scale).setScale(3, RoundingMode.HALF_UP), BigDecimal::add);
        return need;
    }

    /** Fails with the name of the first ingredient that is short, unless the business allows negative stock. */
    @Transactional(readOnly = true)
    public void requireIngredients(Recipe r, BigDecimal quantity, UUID branchId, boolean allowNegative) {
        if (allowNegative) return;
        UUID bid = r.getBusinessId();
        Map<UUID, BigDecimal> need = requirements(r, quantity);
        Map<UUID, ProductVariant> vs = variants.findAllById(need.keySet()).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        for (Map.Entry<UUID, BigDecimal> e : need.entrySet()) {
            ProductVariant v = vs.get(e.getKey());
            BigDecimal have = stock.onHand(v, branchId);
            if (have.compareTo(e.getValue()) < 0) throw ApiException.conflict("Not enough " + nameOf(v) + " to make this. Need " + e.getValue().stripTrailingZeros().toPlainString() + ", have " + have.stripTrailingZeros().toPlainString() + ".");
        }
    }

    /** Take the ingredients out of stock. Ingredient rows are locked first (in id order) so concurrent sales cannot oversell them. */
    @Transactional
    public BigDecimal consume(Recipe r, BigDecimal quantity, UUID branchId, MovementType type, String reference, UUID userId, boolean allowNegative) {
        UUID bid = r.getBusinessId();
        Map<UUID, BigDecimal> need = requirements(r, quantity);
        Map<UUID, ProductVariant> vs = variants.lockAll(bid, need.keySet()).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        BigDecimal cost = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal> e : need.entrySet()) {
            ProductVariant v = vs.get(e.getKey());
            stock.apply(v, branchId, type, e.getValue().negate(), "Used in recipe", reference, userId, allowNegative);
            cost = cost.add(v.getCost().multiply(e.getValue()));
        }
        return Money.scale(cost);
    }
}
