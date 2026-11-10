package com.amsati.shopscale.restaurant;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.catalog.Product;
import com.amsati.shopscale.catalog.ProductRepository;
import com.amsati.shopscale.catalog.ProductStatus;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.CounterService;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.industry.IndustryGuard;
import com.amsati.shopscale.industry.Workflow;
import com.amsati.shopscale.inventory.MovementType;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.notification.NotificationService;
import com.amsati.shopscale.pricing.PricingService;
import com.amsati.shopscale.sales.SaleRepository;
import com.amsati.shopscale.sales.SaleService;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import com.amsati.shopscale.unit.UnitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Table and kitchen orders. An order is an operational record: stock and money move only when the order is paid, through
 * the normal sale path (SaleService), so inventory, sales and the cash ledger stay consistent. Prices are set here on the
 * server from the menu and the chosen modifiers; the client never supplies a price.
 */
@Service
@RequiredArgsConstructor
public class KitchenOrderService {
    private final KitchenOrderRepository orders;
    private final KitchenOrderItemRepository items;
    private final KitchenItemModifierRepository itemMods;
    private final ModifierGroupRepository groups;
    private final ModifierRepository modifiers;
    private final ProductModifierGroupRepository links;
    private final RestaurantTableRepository tables;
    private final ProductVariantRepository variants;
    private final ProductRepository products;
    private final PricingService pricing;
    private final SaleService saleService;
    private final SaleRepository sales;
    private final StockService stock;
    private final UnitService units;
    private final CounterService counters;
    private final IndustryGuard industry;
    private final SubscriptionService subscriptions;
    private final AccessService access;
    private final AuditService audit;
    private final NotificationService notify;

    public record ItemRequest(@NotNull UUID variantId, @NotNull @DecimalMin("0.001") BigDecimal quantity, @Size(max = 300) String note, List<UUID> modifierIds) {}
    public record CreateRequest(UUID tableId, @NotNull String orderType, @Size(max = 150) String customerName, @Size(max = 500) String note,
                                UUID branchId, @NotEmpty @Valid List<ItemRequest> items) {}
    public record AddItemsRequest(@NotEmpty @Valid List<ItemRequest> items) {}
    public record PayRequest(@Valid List<SaleService.PayRequest> payments, @DecimalMin("0") BigDecimal discount, UUID customerId) {}
    public record ItemDto(UUID id, UUID variantId, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal modifiersTotal, BigDecimal lineTotal, String note, String status, List<String> modifiers) {}
    public record OrderDto(UUID id, long number, UUID tableId, String tableName, String orderType, String customerName, String note, String status,
                           UUID saleId, Instant createdAt, BigDecimal total, List<ItemDto> items) {}

    private UUID gate() {
        UUID bid = Tenant.businessId();
        industry.require(bid, Workflow.RESTAURANT);
        subscriptions.requireFeature(bid, "RESTAURANT", "Restaurant management");
        return bid;
    }

    // ---------- create / add ----------
    @Transactional
    public OrderDto create(CreateRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        if (!Set.of("DINE_IN", "TAKEAWAY", "DELIVERY").contains(r.orderType())) throw ApiException.badRequest("Choose dine in, takeaway or delivery");
        UUID branch = stock.resolveBranch(bid, r.branchId());
        RestaurantTable table = null;
        if (r.tableId() != null) {
            table = tables.findByIdAndBusinessId(r.tableId(), bid).filter(RestaurantTable::isActive).orElseThrow(() -> ApiException.badRequest("Table not found"));
        } else if ("DINE_IN".equals(r.orderType())) {
            throw ApiException.badRequest("Choose the table for a dine-in order");
        }
        KitchenOrder o = new KitchenOrder();
        o.setBusinessId(bid);
        o.setBranchId(branch);
        o.setNumber(counters.next(bid, "KITCHEN_ORDER"));
        o.setTableId(table == null ? null : table.getId());
        o.setOrderType(r.orderType());
        o.setCustomerName(blank(r.customerName()));
        o.setNote(blank(r.note()));
        o.setCreatedBy(Tenant.userId());
        try {
            orders.saveAndFlush(o);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.conflict("That table already has an open order. Add to it instead.");
        }
        addInternal(bid, o, r.items());
        audit.record(bid, Tenant.userId(), "KITCHEN_ORDER_CREATED", "kitchen_order", o.getId(), "#" + o.getNumber());
        return view(o);
    }

    @Transactional
    public OrderDto addItems(UUID orderId, AddItemsRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        KitchenOrder o = orders.lockOne(orderId, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!"OPEN".equals(o.getStatus())) throw ApiException.conflict("This order is already closed");
        addInternal(bid, o, r.items());
        return view(o);
    }

    private void addInternal(UUID bid, KitchenOrder o, List<ItemRequest> reqs) {
        Set<UUID> vids = reqs.stream().map(ItemRequest::variantId).collect(Collectors.toSet());
        Map<UUID, ProductVariant> vs = variants.findAllById(vids).stream().filter(v -> v.getBusinessId().equals(bid)).collect(Collectors.toMap(ProductVariant::getId, v -> v));
        if (vs.size() != vids.size()) throw ApiException.badRequest("One of the menu items is no longer available");
        PricingService.Context ctx = pricing.context(bid, null, vids);
        for (ItemRequest ir : reqs) {
            ProductVariant v = vs.get(ir.variantId());
            Product p = v.getProduct();
            if (!v.isActive() || p.getStatus() != ProductStatus.ACTIVE) throw ApiException.badRequest(p.getName() + " is no longer on the menu");
            units.requireValidQuantity(v, ir.quantity());
            List<Modifier> chosen = chooseModifiers(bid, p, ir.modifierIds());
            KitchenOrderItem it = new KitchenOrderItem();
            it.setOrderId(o.getId());
            it.setBusinessId(bid);
            it.setVariantId(v.getId());
            it.setItemName(p.getName() + (v.getLabel() == null || v.getLabel().isBlank() ? "" : " (" + v.getLabel() + ")"));
            it.setQuantity(ir.quantity());
            it.setUnitPrice(Money.scale(ctx.priceFor(v, ir.quantity())));
            it.setModifiersTotal(Money.scale(chosen.stream().map(Modifier::getPriceDelta).reduce(BigDecimal.ZERO, BigDecimal::add)));
            it.setNote(blank(ir.note()));
            items.save(it);
            for (Modifier m : chosen) {
                KitchenItemModifier km = new KitchenItemModifier();
                km.setItemId(it.getId());
                km.setModifierId(m.getId());
                km.setName(m.getName());
                km.setPriceDelta(m.getPriceDelta());
                itemMods.save(km);
            }
        }
    }

    /** Checks the chosen options against the groups attached to this menu item: only its groups, within each group's limits. */
    private List<Modifier> chooseModifiers(UUID bid, Product p, List<UUID> ids) {
        List<UUID> attached = links.findByProductId(p.getId()).stream().map(ProductModifierGroup::getGroupId).toList();
        List<ModifierGroup> gs = attached.isEmpty() ? List.of() : groups.findAllById(attached).stream().filter(g -> g.getBusinessId().equals(bid) && g.isActive()).toList();
        List<Modifier> chosen = new ArrayList<>();
        for (UUID id : ids == null ? List.<UUID>of() : ids) {
            Modifier m = modifiers.findByIdAndBusinessId(id, bid).filter(Modifier::isActive).orElseThrow(() -> ApiException.badRequest("One of the options is not available"));
            if (gs.stream().noneMatch(g -> g.getId().equals(m.getGroupId()))) throw ApiException.badRequest(m.getName() + " is not an option for " + p.getName());
            chosen.add(m);
        }
        for (ModifierGroup g : gs) {
            long n = chosen.stream().filter(m -> m.getGroupId().equals(g.getId())).count();
            if (g.isRequired() && n == 0) throw ApiException.badRequest("Choose an option for " + g.getName() + " on " + p.getName());
            if (n > g.getMaxSelect()) throw ApiException.badRequest("Choose at most " + g.getMaxSelect() + " for " + g.getName() + " on " + p.getName());
        }
        return chosen;
    }

    // ---------- read ----------
    @Transactional(readOnly = true)
    public List<OrderDto> open() {
        UUID bid = Tenant.businessId();
        return viewAll(orders.findByBusinessIdAndStatusOrderByCreatedAt(bid, "OPEN"));
    }

    @Transactional(readOnly = true)
    public List<OrderDto> recent() {
        return viewAll(orders.findTop50ByBusinessIdOrderByCreatedAtDesc(Tenant.businessId()));
    }

    /** Kitchen display: open orders with only the items still to be made or handed over. */
    @Transactional(readOnly = true)
    public List<OrderDto> board() {
        return open().stream().map(o -> new OrderDto(o.id(), o.number(), o.tableId(), o.tableName(), o.orderType(), o.customerName(), o.note(), o.status(), o.saleId(), o.createdAt(), o.total(),
            o.items().stream().filter(i -> Set.of("NEW", "PREPARING", "READY").contains(i.status())).toList())).filter(o -> !o.items().isEmpty()).toList();
    }

    @Transactional(readOnly = true)
    public OrderDto get(UUID id) {
        return view(orders.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Order not found")));
    }

    private OrderDto view(KitchenOrder o) { return viewAll(List.of(o)).get(0); }

    private List<OrderDto> viewAll(List<KitchenOrder> os) {
        if (os.isEmpty()) return List.of();
        List<UUID> oids = os.stream().map(KitchenOrder::getId).toList();
        Map<UUID, List<KitchenOrderItem>> byOrder = items.findByOrderIdIn(oids).stream().sorted(Comparator.comparing(KitchenOrderItem::getCreatedAt)).collect(Collectors.groupingBy(KitchenOrderItem::getOrderId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, List<KitchenItemModifier>> mods = itemMods.findByItemIdIn(byOrder.values().stream().flatMap(List::stream).map(KitchenOrderItem::getId).toList()).stream().collect(Collectors.groupingBy(KitchenItemModifier::getItemId));
        Map<UUID, String> tableNames = tables.findAllById(os.stream().map(KitchenOrder::getTableId).filter(Objects::nonNull).collect(Collectors.toSet())).stream().collect(Collectors.toMap(RestaurantTable::getId, RestaurantTable::getName));
        List<OrderDto> out = new ArrayList<>();
        for (KitchenOrder o : os) {
            List<ItemDto> its = byOrder.getOrDefault(o.getId(), List.of()).stream().map(i -> new ItemDto(i.getId(), i.getVariantId(), i.getItemName(), i.getQuantity(), i.getUnitPrice(), i.getModifiersTotal(),
                Money.scale(i.getUnitPrice().add(i.getModifiersTotal()).multiply(i.getQuantity())), i.getNote(), i.getStatus(), mods.getOrDefault(i.getId(), List.of()).stream().map(KitchenItemModifier::getName).toList())).toList();
            BigDecimal total = its.stream().filter(i -> !"CANCELLED".equals(i.status())).map(ItemDto::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new OrderDto(o.getId(), o.getNumber(), o.getTableId(), o.getTableId() == null ? null : tableNames.get(o.getTableId()), o.getOrderType(), o.getCustomerName(), o.getNote(), o.getStatus(), o.getSaleId(), o.getCreatedAt(), Money.scale(total), its));
        }
        return out;
    }

    // ---------- kitchen progress ----------
    private static final Map<String, Set<String>> NEXT = Map.of("NEW", Set.of("PREPARING", "CANCELLED"), "PREPARING", Set.of("READY", "CANCELLED"), "READY", Set.of("SERVED"), "SERVED", Set.of(), "CANCELLED", Set.of());

    @Transactional
    public OrderDto setItemStatus(UUID itemId, String status) {
        UUID bid = gate();
        KitchenOrderItem it = items.findByIdAndBusinessId(itemId, bid).orElseThrow(() -> ApiException.notFound("Item not found"));
        KitchenOrder o = orders.lockOne(it.getOrderId(), bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!"OPEN".equals(o.getStatus())) throw ApiException.conflict("This order is already closed");
        if (!NEXT.getOrDefault(it.getStatus(), Set.of()).contains(status)) throw ApiException.conflict("That item cannot move from " + it.getStatus().toLowerCase() + " to " + status.toLowerCase());
        switch (status) {
            case "PREPARING" -> access.require(Permission.KITCHEN_STATUS_UPDATE);
            case "READY" -> access.require(Permission.KITCHEN_MARK_READY);
            case "SERVED" -> { if (!access.current().contains(Permission.SALES_CREATE)) access.require(Permission.KITCHEN_STATUS_UPDATE); }
            default -> access.require(Permission.SALES_CREATE);
        }
        it.setStatus(status);
        if ("READY".equals(status)) notify.publish(bid, Permission.SALES_CREATE, "ORDER_READY", it.getItemName() + " is ready", "Order #" + o.getNumber(), "/more/restaurant", null);
        return view(o);
    }

    @Transactional
    public OrderDto cancel(UUID orderId) {
        UUID bid = gate();
        KitchenOrder o = orders.lockOne(orderId, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!"OPEN".equals(o.getStatus())) throw ApiException.conflict("This order is already closed");
        // Nothing was deducted from stock or recorded as money yet, so cancelling only closes the order.
        for (KitchenOrderItem i : items.findByOrderIdOrderByCreatedAt(o.getId())) if (!"CANCELLED".equals(i.getStatus())) i.setStatus("CANCELLED");
        o.setStatus("CANCELLED");
        audit.record(bid, Tenant.userId(), "KITCHEN_ORDER_CANCELLED", "kitchen_order", o.getId(), "#" + o.getNumber());
        return view(o);
    }

    // ---------- payment ----------
    /** Turns the order into a real sale (stock, ledger, reports) in one transaction. */
    @Transactional
    public OrderDto pay(UUID orderId, PayRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        access.require(Permission.SALES_CREATE);
        KitchenOrder o = orders.lockOne(orderId, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!"OPEN".equals(o.getStatus())) throw ApiException.conflict("This order is already closed");
        List<KitchenOrderItem> live = items.findByOrderIdOrderByCreatedAt(o.getId()).stream().filter(i -> !"CANCELLED".equals(i.getStatus())).toList();
        if (live.isEmpty()) throw ApiException.badRequest("There is nothing to pay on this order");
        Map<UUID, BigDecimal> qty = new LinkedHashMap<>();
        BigDecimal surcharge = BigDecimal.ZERO;
        for (KitchenOrderItem i : live) {
            qty.merge(i.getVariantId(), i.getQuantity(), BigDecimal::add);
            surcharge = surcharge.add(i.getModifiersTotal().multiply(i.getQuantity()));
        }
        SaleService.SaleRequest req = new SaleService.SaleRequest(
            qty.entrySet().stream().map(e -> new SaleService.ItemRequest(e.getKey(), e.getValue(), null)).toList(),
            r.discount() == null ? BigDecimal.ZERO : r.discount(), r.customerId(), r.payments(),
            "Order #" + o.getNumber() + (o.getTableId() == null ? "" : " · table"), "ko-" + o.getId(), o.getBranchId());
        SaleService.SaleDto sale = saleService.createInternal(req, "RESTAURANT", null, null, false, surcharge);

        // Ingredients used by chosen modifiers (for example extra cheese) come out of stock too, and count as cost.
        Map<UUID, BigDecimal> extraUse = new LinkedHashMap<>();
        for (KitchenItemModifier km : itemMods.findByItemIdIn(live.stream().map(KitchenOrderItem::getId).toList())) {
            Modifier m = modifiers.findById(km.getModifierId()).orElse(null);
            if (m == null || m.getIngredientVariantId() == null) continue;
            BigDecimal itemQty = live.stream().filter(i -> i.getId().equals(km.getItemId())).findFirst().map(KitchenOrderItem::getQuantity).orElse(BigDecimal.ONE);
            extraUse.merge(m.getIngredientVariantId(), m.getIngredientQuantity().multiply(itemQty), BigDecimal::add);
        }
        if (!extraUse.isEmpty()) {
            Map<UUID, ProductVariant> locked = variants.lockAll(bid, extraUse.keySet()).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
            BigDecimal cost = BigDecimal.ZERO;
            for (Map.Entry<UUID, BigDecimal> e : extraUse.entrySet()) {
                ProductVariant v = locked.get(e.getKey());
                stock.apply(v, o.getBranchId(), MovementType.SALE, e.getValue().negate(), "Menu option", "SALE-" + sale.saleNumber(), Tenant.userId(), true);
                cost = cost.add(v.getCost().multiply(e.getValue()));
            }
            BigDecimal finalCost = cost;
            sales.findById(sale.id()).ifPresent(s -> s.setCostTotal(s.getCostTotal().add(Money.scale(finalCost))));
        }
        o.setStatus("PAID");
        o.setSaleId(sale.id());
        for (KitchenOrderItem i : live) if (!"SERVED".equals(i.getStatus())) i.setStatus("SERVED");
        audit.record(bid, Tenant.userId(), "KITCHEN_ORDER_PAID", "kitchen_order", o.getId(), "#" + o.getNumber() + " sale #" + sale.saleNumber());
        return view(o);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
