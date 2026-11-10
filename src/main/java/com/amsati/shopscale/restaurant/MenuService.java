package com.amsati.shopscale.restaurant;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.catalog.ProductRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.industry.IndustryGuard;
import com.amsati.shopscale.industry.Workflow;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Modifier groups (extras, sizes, "no onions") and dining tables. */
@Service
@RequiredArgsConstructor
public class MenuService {
    private final ModifierGroupRepository groups;
    private final ModifierRepository modifiers;
    private final ProductModifierGroupRepository links;
    private final RestaurantTableRepository tables;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final IndustryGuard industry;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    public record GroupRequest(@NotBlank @Size(max = 80) String name, boolean required, @Min(1) @Max(20) int maxSelect) {}
    public record ModifierRequest(@NotBlank @Size(max = 80) String name, @NotNull @DecimalMin("0") BigDecimal priceDelta,
                                  UUID ingredientVariantId, @DecimalMin("0.001") BigDecimal ingredientQuantity) {}
    public record ModifierDto(UUID id, String name, BigDecimal priceDelta, UUID ingredientVariantId, BigDecimal ingredientQuantity, boolean active) {}
    public record GroupDto(UUID id, String name, boolean required, int maxSelect, boolean active, List<ModifierDto> modifiers) {}
    public record TableRequest(@NotBlank @Size(max = 40) String name, @Min(1) @Max(100) int seats) {}
    public record TableDto(UUID id, String name, int seats, boolean active) {}

    private UUID gate() {
        UUID bid = Tenant.businessId();
        industry.require(bid, Workflow.RESTAURANT);
        subscriptions.requireFeature(bid, "RESTAURANT", "Restaurant management");
        return bid;
    }

    // ----- modifier groups -----
    @Transactional(readOnly = true)
    public List<GroupDto> listGroups() {
        UUID bid = Tenant.businessId();
        List<ModifierGroup> gs = groups.findByBusinessIdOrderByName(bid);
        Map<UUID, List<Modifier>> ms = gs.isEmpty() ? Map.of() : modifiers.findByGroupIdIn(gs.stream().map(ModifierGroup::getId).toList()).stream().collect(Collectors.groupingBy(Modifier::getGroupId));
        return gs.stream().map(g -> groupDto(g, ms.getOrDefault(g.getId(), List.of()))).toList();
    }

    private static GroupDto groupDto(ModifierGroup g, List<Modifier> ms) {
        return new GroupDto(g.getId(), g.getName(), g.isRequired(), g.getMaxSelect(), g.isActive(),
            ms.stream().sorted(Comparator.comparing(Modifier::getName)).map(m -> new ModifierDto(m.getId(), m.getName(), m.getPriceDelta(), m.getIngredientVariantId(), m.getIngredientQuantity(), m.isActive())).toList());
    }

    @Transactional
    public GroupDto createGroup(GroupRequest r) {
        UUID bid = gate();
        if (groups.existsByBusinessIdAndNameIgnoreCase(bid, r.name().trim())) throw ApiException.conflict("You already have a group called " + r.name().trim());
        ModifierGroup g = new ModifierGroup();
        g.setBusinessId(bid);
        g.setName(r.name().trim());
        g.setRequired(r.required());
        g.setMaxSelect(r.maxSelect());
        groups.save(g);
        audit.record(bid, Tenant.userId(), "MODIFIER_GROUP_CREATED", "modifier_group", g.getId(), g.getName());
        return groupDto(g, List.of());
    }

    @Transactional
    public GroupDto updateGroup(UUID id, GroupRequest r, Boolean active) {
        UUID bid = gate();
        ModifierGroup g = groups.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Group not found"));
        g.setName(r.name().trim());
        g.setRequired(r.required());
        g.setMaxSelect(r.maxSelect());
        if (active != null) g.setActive(active);
        return groupDto(g, modifiers.findByGroupId(g.getId()));
    }

    @Transactional
    public GroupDto addModifier(UUID groupId, ModifierRequest r) {
        UUID bid = gate();
        ModifierGroup g = groups.findByIdAndBusinessId(groupId, bid).orElseThrow(() -> ApiException.notFound("Group not found"));
        Modifier m = new Modifier();
        m.setGroupId(g.getId());
        m.setBusinessId(bid);
        apply(bid, m, r);
        modifiers.save(m);
        return groupDto(g, modifiers.findByGroupId(g.getId()));
    }

    @Transactional
    public GroupDto updateModifier(UUID id, ModifierRequest r, Boolean active) {
        UUID bid = gate();
        Modifier m = modifiers.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Option not found"));
        apply(bid, m, r);
        if (active != null) m.setActive(active);
        ModifierGroup g = groups.findById(m.getGroupId()).orElseThrow();
        return groupDto(g, modifiers.findByGroupId(g.getId()));
    }

    private void apply(UUID bid, Modifier m, ModifierRequest r) {
        m.setName(r.name().trim());
        m.setPriceDelta(Money.scale(r.priceDelta()));
        if (r.ingredientVariantId() != null) {
            variants.findById(r.ingredientVariantId()).filter(v -> v.getBusinessId().equals(bid)).orElseThrow(() -> ApiException.badRequest("Ingredient not found"));
            if (r.ingredientQuantity() == null) throw ApiException.badRequest("Enter how much of the ingredient this option uses");
        }
        m.setIngredientVariantId(r.ingredientVariantId());
        m.setIngredientQuantity(r.ingredientVariantId() == null ? null : r.ingredientQuantity());
    }

    @Transactional(readOnly = true)
    public List<UUID> groupsOfProduct(UUID productId) {
        UUID bid = Tenant.businessId();
        products.findById(productId).filter(p -> p.getBusinessId().equals(bid)).orElseThrow(() -> ApiException.notFound("Product not found"));
        return links.findByProductId(productId).stream().map(ProductModifierGroup::getGroupId).toList();
    }

    @Transactional
    public List<UUID> setGroupsOfProduct(UUID productId, List<UUID> groupIds) {
        UUID bid = gate();
        products.findById(productId).filter(p -> p.getBusinessId().equals(bid)).orElseThrow(() -> ApiException.notFound("Product not found"));
        Set<UUID> ids = new LinkedHashSet<>(groupIds == null ? List.of() : groupIds);
        for (UUID g : ids) groups.findByIdAndBusinessId(g, bid).orElseThrow(() -> ApiException.badRequest("Group not found"));
        links.deleteByProduct(productId);
        links.flush();
        for (UUID g : ids) links.save(new ProductModifierGroup(productId, g));
        audit.record(bid, Tenant.userId(), "MENU_ITEM_MODIFIERS_SET", "product", productId, ids.size() + " groups");
        return new ArrayList<>(ids);
    }

    // ----- tables -----
    @Transactional(readOnly = true)
    public List<TableDto> listTables() {
        return tables.findByBusinessIdOrderByName(Tenant.businessId()).stream().map(t -> new TableDto(t.getId(), t.getName(), t.getSeats(), t.isActive())).toList();
    }

    @Transactional
    public TableDto createTable(TableRequest r) {
        UUID bid = gate();
        if (tables.existsByBusinessIdAndNameIgnoreCase(bid, r.name().trim())) throw ApiException.conflict("You already have a table called " + r.name().trim());
        RestaurantTable t = new RestaurantTable();
        t.setBusinessId(bid);
        t.setName(r.name().trim());
        t.setSeats(r.seats());
        tables.save(t);
        return new TableDto(t.getId(), t.getName(), t.getSeats(), t.isActive());
    }

    @Transactional
    public TableDto updateTable(UUID id, TableRequest r, Boolean active) {
        UUID bid = gate();
        RestaurantTable t = tables.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Table not found"));
        t.setName(r.name().trim());
        t.setSeats(r.seats());
        if (active != null) t.setActive(active);
        return new TableDto(t.getId(), t.getName(), t.getSeats(), t.isActive());
    }
}
