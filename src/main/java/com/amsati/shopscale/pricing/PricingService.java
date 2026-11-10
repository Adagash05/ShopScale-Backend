package com.amsati.shopscale.pricing;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.customer.Customer;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side price resolution. The price used for a sale line is ALWAYS decided here, never taken from the client.
 * Precedence: a price agreed with that customer, then the customer's price tier, then bulk breaks for everyone,
 * then the variant's standard price. Within each level the highest minimum-quantity that the quantity reaches wins.
 */
@Service
@RequiredArgsConstructor
public class PricingService {
    private final PriceTierRepository tiers;
    private final VariantPriceRepository prices;
    private final ProductVariantRepository variants;
    private final CustomerRepository customers;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    /** Everything needed to price a basket for one customer without more queries. */
    public static final class Context {
        private final UUID customerId;
        private final UUID tierId;
        private final Map<UUID, List<VariantPrice>> byVariant;

        Context(UUID customerId, UUID tierId, Map<UUID, List<VariantPrice>> byVariant) {
            this.customerId = customerId;
            this.tierId = tierId;
            this.byVariant = byVariant;
        }

        public BigDecimal priceFor(ProductVariant v, BigDecimal quantity) {
            List<VariantPrice> rules = byVariant.getOrDefault(v.getId(), List.of());
            BigDecimal best = pick(rules, quantity, r -> customerId != null && customerId.equals(r.getCustomerId()));
            if (best == null && tierId != null) best = pick(rules, quantity, r -> r.getCustomerId() == null && tierId.equals(r.getPriceTierId()));
            if (best == null) best = pick(rules, quantity, r -> r.getCustomerId() == null && r.getPriceTierId() == null);
            return best == null ? v.getPrice() : best;
        }

        private static BigDecimal pick(List<VariantPrice> rules, BigDecimal q, java.util.function.Predicate<VariantPrice> level) {
            return rules.stream().filter(level).filter(r -> r.getMinQuantity().compareTo(q) <= 0)
                .max(Comparator.comparing(VariantPrice::getMinQuantity)).map(VariantPrice::getPrice).orElse(null);
        }
    }

    /** Customer must already be tenant-checked by the caller (or null for a walk-in sale). */
    @Transactional(readOnly = true)
    public Context context(UUID businessId, Customer customer, Collection<UUID> variantIds) {
        Map<UUID, List<VariantPrice>> map = new HashMap<>();
        if (!variantIds.isEmpty()) {
            for (VariantPrice p : prices.forVariants(businessId, variantIds)) map.computeIfAbsent(p.getVariantId(), k -> new java.util.ArrayList<>()).add(p);
        }
        return new Context(customer == null ? null : customer.getId(), customer == null ? null : customer.getPriceTierId(), map);
    }

    // ---------- management ----------
    public record TierRequest(@NotBlank @Size(max = 80) String name) {}
    public record TierDto(UUID id, String name, boolean active) {}
    public record RuleRequest(@NotNull UUID variantId, UUID priceTierId, UUID customerId,
                              @NotNull @DecimalMin(value = "0.001", message = "Minimum quantity must be above zero") BigDecimal minQuantity,
                              @NotNull @DecimalMin(value = "0", message = "Price cannot be negative") BigDecimal price) {}
    public record RuleDto(UUID id, UUID variantId, UUID priceTierId, String tierName, UUID customerId, String customerName, BigDecimal minQuantity, BigDecimal price) {}

    @Transactional(readOnly = true)
    public List<TierDto> listTiers() {
        return tiers.findByBusinessIdOrderByName(Tenant.businessId()).stream().map(t -> new TierDto(t.getId(), t.getName(), t.isActive())).toList();
    }

    @Transactional
    public TierDto createTier(TierRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireFeature(bid, "WHOLESALE_PRICING", "Wholesale price tiers");
        String name = r.name().trim();
        if (tiers.existsByBusinessIdAndNameIgnoreCase(bid, name)) throw ApiException.conflict("You already have a price tier called " + name);
        PriceTier t = new PriceTier();
        t.setBusinessId(bid);
        t.setName(name);
        tiers.save(t);
        audit.record(bid, Tenant.userId(), "PRICE_TIER_CREATED", "price_tier", t.getId(), name);
        return new TierDto(t.getId(), t.getName(), t.isActive());
    }

    @Transactional
    public TierDto setTierActive(UUID id, boolean active) {
        PriceTier t = tiers.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Price tier not found"));
        t.setActive(active);
        return new TierDto(t.getId(), t.getName(), t.isActive());
    }

    @Transactional(readOnly = true)
    public List<RuleDto> rulesFor(UUID variantId) {
        UUID bid = Tenant.businessId();
        Map<UUID, String> tierNames = new HashMap<>();
        tiers.findByBusinessIdOrderByName(bid).forEach(t -> tierNames.put(t.getId(), t.getName()));
        return prices.forVariant(bid, variantId).stream().map(p -> new RuleDto(p.getId(), p.getVariantId(), p.getPriceTierId(),
            tierNames.get(p.getPriceTierId()), p.getCustomerId(),
            p.getCustomerId() == null ? null : customers.findByIdAndBusinessId(p.getCustomerId(), bid).map(Customer::getName).orElse(null),
            p.getMinQuantity(), p.getPrice())).toList();
    }

    @Transactional
    public RuleDto addRule(RuleRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireFeature(bid, "WHOLESALE_PRICING", "Wholesale and bulk pricing");
        if (r.priceTierId() != null && r.customerId() != null) throw ApiException.badRequest("A price is for a tier or for one customer, not both");
        variants.findById(r.variantId()).filter(v -> v.getBusinessId().equals(bid)).orElseThrow(() -> ApiException.badRequest("Product option not found"));
        if (r.priceTierId() != null) tiers.findByIdAndBusinessId(r.priceTierId(), bid).orElseThrow(() -> ApiException.badRequest("Price tier not found"));
        if (r.customerId() != null) customers.findByIdAndBusinessId(r.customerId(), bid).orElseThrow(() -> ApiException.badRequest("Customer not found"));
        for (VariantPrice existing : prices.forVariant(bid, r.variantId())) {
            if (java.util.Objects.equals(existing.getPriceTierId(), r.priceTierId()) && java.util.Objects.equals(existing.getCustomerId(), r.customerId())
                && existing.getMinQuantity().compareTo(r.minQuantity()) == 0) {
                existing.setPrice(Money.scale(r.price()));
                audit.record(bid, Tenant.userId(), "PRICE_RULE_UPDATED", "variant", r.variantId(), "min " + r.minQuantity());
                return new RuleDto(existing.getId(), existing.getVariantId(), existing.getPriceTierId(), null, existing.getCustomerId(), null, existing.getMinQuantity(), existing.getPrice());
            }
        }
        VariantPrice p = new VariantPrice();
        p.setBusinessId(bid);
        p.setVariantId(r.variantId());
        p.setPriceTierId(r.priceTierId());
        p.setCustomerId(r.customerId());
        p.setMinQuantity(r.minQuantity());
        p.setPrice(Money.scale(r.price()));
        prices.save(p);
        audit.record(bid, Tenant.userId(), "PRICE_RULE_ADDED", "variant", r.variantId(), "min " + r.minQuantity() + " price " + p.getPrice());
        return new RuleDto(p.getId(), p.getVariantId(), p.getPriceTierId(), null, p.getCustomerId(), null, p.getMinQuantity(), p.getPrice());
    }

    @Transactional
    public void deleteRule(UUID id) {
        UUID bid = Tenant.businessId();
        VariantPrice p = prices.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Price not found"));
        prices.delete(p);
        audit.record(bid, Tenant.userId(), "PRICE_RULE_DELETED", "variant", p.getVariantId(), "min " + p.getMinQuantity());
    }

    /** Price shown in the POS search for the chosen customer (quantity 1); the real sale re-prices on the server. */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> displayPrices(UUID customerId, Collection<ProductVariant> vs) {
        UUID bid = Tenant.businessId();
        Customer c = customerId == null ? null : customers.findByIdAndBusinessId(customerId, bid).orElse(null);
        Context ctx = context(bid, c, vs.stream().map(ProductVariant::getId).toList());
        Map<UUID, BigDecimal> out = new HashMap<>();
        for (ProductVariant v : vs) out.put(v.getId(), ctx.priceFor(v, BigDecimal.ONE));
        return out;
    }
}
