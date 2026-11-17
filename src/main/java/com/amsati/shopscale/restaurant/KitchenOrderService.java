package com.amsati.shopscale.restaurant;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.recipe.Recipe;
import com.amsati.shopscale.recipe.RecipeService;
import com.amsati.shopscale.sales.Sale;
import com.amsati.shopscale.sales.SaleReturnService;
import com.amsati.shopscale.sales.SaleStatus;
import com.amsati.shopscale.wastage.WastageService;
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
    private final RecipeService recipes;
    private final WastageService wastage;
    private final SaleReturnService returns;
    private final BusinessRepository businesses;

    public record ItemRequest(@NotNull UUID variantId, @NotNull @DecimalMin("0.001") BigDecimal quantity, @Size(max = 300) String note, List<UUID> modifierIds) {}
    public record CreateRequest(UUID tableId, @NotNull String orderType, @Size(max = 150) String customerName, @Size(max = 500) String note,
                                UUID branchId, @Size(max = 64) String clientRequestId, @NotEmpty @Valid List<ItemRequest> items) {}
    public record AddItemsRequest(@NotEmpty @Valid List<ItemRequest> items) {}
    public record UpdateItemRequest(@DecimalMin("0.001") BigDecimal quantity, @Size(max = 300) String note) {}
    public record CancelRequest(@Size(max = 300) String reason, boolean wasted) {}
    public record ReverseRequest(@NotNull @Size(min = 3, max = 300, message = "Say why this order is being reversed") String reason, boolean restock) {}
    public record PayRequest(@Valid List<SaleService.PayRequest> payments, @DecimalMin("0") BigDecimal discount, UUID customerId) {}
    /** Money fields are null for staff who may not see money (kitchen staff). */
    public record ItemDto(UUID id, UUID variantId, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal modifiersTotal, BigDecimal lineTotal, String note, String status,
                          List<String> modifiers, Instant createdAt, Instant startedAt, Instant readyAt, String cancelReason) {}
    /** status = OPEN/PAID/CANCELLED (the money side); stage = PENDING/ACCEPTED/PREPARING/READY/SERVED/CANCELLED (the kitchen side); paymentStatus = UNPAID/PARTIAL/PAID/REFUNDED. */
    public record OrderDto(UUID id, long number, UUID tableId, String tableName, String orderType, String customerName, String note, String status, String stage, String paymentStatus,
                           UUID saleId, Instant createdAt, Instant acceptedAt, BigDecimal total, BigDecimal amountPaid, BigDecimal amountOwed, String cancelReason, boolean reversed, List<ItemDto> items) {}

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
        // A double tap or a retried request returns the order already made.
        String rid = blank(r.clientRequestId());
        if (rid != null) {
            Optional<KitchenOrder> prior = orders.findByBusinessIdAndClientRequestId(bid, rid);
            if (prior.isPresent()) return view(prior.get());
        }
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
        o.setClientRequestId(rid);
        try {
            orders.saveAndFlush(o);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.conflict("That table already has an open order. Add to it instead.");
        }
        addInternal(bid, o, r.items());
        notify.publish(bid, Permission.KITCHEN_VIEW, "NEW_ORDER", "New order #" + o.getNumber(), o.getOrderType().replace('_', ' ').toLowerCase(), "/kitchen", null);
        audit.record(bid, Tenant.userId(), "KITCHEN_ORDER_CREATED", "kitchen_order", o.getId(), "#" + o.getNumber());
        return view(o);
    }

    @Transactional
    public OrderDto addItems(UUID orderId, AddItemsRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        KitchenOrder o = orders.lockOne(orderId, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        requireOpen(o);
        addInternal(bid, o, r.items());
        audit.record(bid, Tenant.userId(), "KITCHEN_ORDER_ITEMS_ADDED", "kitchen_order", o.getId(), "#" + o.getNumber());
        return view(o);
    }

    private static void requireOpen(KitchenOrder o) {
        if ("CANCELLED".equals(o.getStatus())) throw ApiException.conflict("This order was cancelled");
        if (!"OPEN".equals(o.getStatus())) throw ApiException.conflict("This order is already paid");
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
            if (p.isSoldOut()) throw ApiException.conflict(p.getName() + " is sold out");
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

    /** Change the quantity or note of an item the kitchen has not started. Price is never taken from the client. */
    @Transactional
    public OrderDto updateItem(UUID itemId, UpdateItemRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        KitchenOrderItem it = items.findByIdAndBusinessId(itemId, bid).orElseThrow(() -> ApiException.notFound("Item not found"));
        KitchenOrder o = orders.lockOne(it.getOrderId(), bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        requireOpen(o);
        if (!access.current().contains(Permission.SALES_CREATE)) access.require(Permission.KITCHEN_STATUS_UPDATE);
        if (!"NEW".equals(it.getStatus())) throw ApiException.conflict("The kitchen has already started this item. Add a new item or ask the manager to cancel it.");
        if (r.quantity() != null) {
            ProductVariant v = variants.findById(it.getVariantId()).orElseThrow(() -> ApiException.badRequest("Item not found"));
            units.requireValidQuantity(v, r.quantity());
            it.setQuantity(r.quantity());
        }
        if (r.note() != null) it.setNote(blank(r.note()));
        audit.record(bid, Tenant.userId(), "KITCHEN_ITEM_CHANGED", "kitchen_order", o.getId(), "#" + o.getNumber() + " " + it.getItemName());
        return view(o);
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

    /** Kitchen display: every order that still has something to make or hand over (paid or not), with only those items. */
    @Transactional(readOnly = true)
    public List<OrderDto> board() {
        return viewAll(orders.findBoard(Tenant.businessId())).stream().map(o -> new OrderDto(o.id(), o.number(), o.tableId(), o.tableName(), o.orderType(), o.customerName(), o.note(), o.status(), o.stage(),
            o.paymentStatus(), o.saleId(), o.createdAt(), o.acceptedAt(), o.total(), o.amountPaid(), o.amountOwed(), o.cancelReason(), o.reversed(),
            o.items().stream().filter(i -> Set.of("NEW", "PREPARING", "READY").contains(i.status())).toList())).filter(o -> !o.items().isEmpty()).toList();
    }

    @Transactional(readOnly = true)
    public OrderDto get(UUID id) {
        return view(orders.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Order not found")));
    }

    private OrderDto view(KitchenOrder o) { return viewAll(List.of(o)).get(0); }

    /** Kitchen-side stage, worked out from the items so it can never disagree with them. */
    static String stage(KitchenOrder o, List<String> itemStatuses) {
        if ("CANCELLED".equals(o.getStatus())) return "CANCELLED";
        List<String> live = itemStatuses.stream().filter(s -> !"CANCELLED".equals(s)).toList();
        if (!live.isEmpty() && live.stream().allMatch("SERVED"::equals)) return "SERVED";
        if (!live.isEmpty() && live.stream().allMatch(s -> "READY".equals(s) || "SERVED".equals(s))) return "READY";
        if (live.stream().anyMatch(s -> !"NEW".equals(s))) return "PREPARING";
        return o.getAcceptedAt() != null ? "ACCEPTED" : "PENDING";
    }

    private List<OrderDto> viewAll(List<KitchenOrder> os) {
        if (os.isEmpty()) return List.of();
        boolean money = access.current().contains(Permission.SALES_CREATE) || access.current().contains(Permission.SALES_HISTORY_VIEW);
        List<UUID> oids = os.stream().map(KitchenOrder::getId).toList();
        Map<UUID, List<KitchenOrderItem>> byOrder = items.findByOrderIdIn(oids).stream().sorted(Comparator.comparing(KitchenOrderItem::getCreatedAt)).collect(Collectors.groupingBy(KitchenOrderItem::getOrderId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, List<KitchenItemModifier>> mods = itemMods.findByItemIdIn(byOrder.values().stream().flatMap(List::stream).map(KitchenOrderItem::getId).toList()).stream().collect(Collectors.groupingBy(KitchenItemModifier::getItemId));
        Map<UUID, String> tableNames = tables.findAllById(os.stream().map(KitchenOrder::getTableId).filter(Objects::nonNull).collect(Collectors.toSet())).stream().collect(Collectors.toMap(RestaurantTable::getId, RestaurantTable::getName));
        Map<UUID, Sale> saleById = sales.findAllById(os.stream().map(KitchenOrder::getSaleId).filter(Objects::nonNull).collect(Collectors.toSet())).stream().collect(Collectors.toMap(Sale::getId, x -> x));
        List<OrderDto> out = new ArrayList<>();
        for (KitchenOrder o : os) {
            List<KitchenOrderItem> raw = byOrder.getOrDefault(o.getId(), List.of());
            List<ItemDto> its = raw.stream().map(i -> new ItemDto(i.getId(), i.getVariantId(), i.getItemName(), i.getQuantity(),
                money ? i.getUnitPrice() : null, money ? i.getModifiersTotal() : null,
                money ? Money.scale(i.getUnitPrice().add(i.getModifiersTotal()).multiply(i.getQuantity())) : null,
                i.getNote(), i.getStatus(), mods.getOrDefault(i.getId(), List.of()).stream().map(KitchenItemModifier::getName).toList(),
                i.getCreatedAt(), i.getStartedAt(), i.getReadyAt(), i.getCancelReason())).toList();
            BigDecimal total = raw.stream().filter(i -> !"CANCELLED".equals(i.getStatus()))
                .map(i -> Money.scale(i.getUnitPrice().add(i.getModifiersTotal()).multiply(i.getQuantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
            Sale sale = o.getSaleId() == null ? null : saleById.get(o.getSaleId());
            String pay;
            BigDecimal paid = BigDecimal.ZERO, owed = BigDecimal.ZERO;
            if (sale == null) pay = "UNPAID";
            else {
                paid = sale.getAmountPaid();
                owed = sale.getAmountOwed();
                total = sale.getTotal();
                pay = o.getReversedAt() != null || sale.getStatus() == SaleStatus.VOIDED ? "REFUNDED" : owed.signum() > 0 ? "PARTIAL" : "PAID";
            }
            out.add(new OrderDto(o.getId(), o.getNumber(), o.getTableId(), o.getTableId() == null ? null : tableNames.get(o.getTableId()), o.getOrderType(), o.getCustomerName(), o.getNote(),
                o.getStatus(), stage(o, raw.stream().map(KitchenOrderItem::getStatus).toList()), pay, o.getSaleId(), o.getCreatedAt(), o.getAcceptedAt(),
                money ? Money.scale(total) : null, money ? paid : null, money ? owed : null, o.getCancelReason(), o.getReversedAt() != null, its));
        }
        return out;
    }

    // ---------- kitchen progress ----------
    private static final Map<String, Set<String>> NEXT = Map.of("NEW", Set.of("PREPARING"), "PREPARING", Set.of("READY"), "READY", Set.of("SERVED"), "SERVED", Set.of(), "CANCELLED", Set.of());

    /** Kitchen accepts the order. Safe to repeat. */
    @Transactional
    public OrderDto accept(UUID orderId) {
        UUID bid = gate();
        KitchenOrder o = orders.lockOne(orderId, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if ("CANCELLED".equals(o.getStatus())) throw ApiException.conflict("This order was cancelled");
        if (!access.current().contains(Permission.KITCHEN_STATUS_UPDATE)) access.require(Permission.SALES_CREATE);
        if (o.getAcceptedAt() != null) return view(o);
        List<KitchenOrderItem> live = items.findByOrderIdOrderByCreatedAt(o.getId()).stream().filter(i -> !"CANCELLED".equals(i.getStatus())).toList();
        checkAvailability(bid, o.getBranchId(), live);
        o.setAcceptedAt(Instant.now());
        o.setAcceptedBy(Tenant.userId());
        audit.record(bid, Tenant.userId(), "KITCHEN_ORDER_ACCEPTED", "kitchen_order", o.getId(), "#" + o.getNumber());
        return view(o);
    }

    /** Warns before cooking starts if an ingredient is short. Stock itself is taken when the order is paid. */
    private void checkAvailability(UUID bid, UUID branchId, List<KitchenOrderItem> its) {
        if (businesses.findById(bid).map(b -> b.isAllowNegativeStock()).orElse(false)) return;
        Map<UUID, Recipe> made = recipes.madeToOrder(bid, its.stream().map(KitchenOrderItem::getVariantId).collect(Collectors.toSet()));
        for (KitchenOrderItem it : its) {
            Recipe r = made.get(it.getVariantId());
            if (r != null) recipes.requireIngredients(r, it.getQuantity(), branchId, false);
            else {
                ProductVariant v = variants.findById(it.getVariantId()).orElseThrow(() -> ApiException.badRequest("Item not found"));
                BigDecimal have = stock.onHand(v, branchId);
                if (have.compareTo(it.getQuantity()) < 0) throw ApiException.conflict("Not enough " + it.getItemName() + " in stock. Only " + have.stripTrailingZeros().toPlainString() + " left.");
            }
        }
    }

    @Transactional
    public OrderDto setItemStatus(UUID itemId, String status) {
        UUID bid = gate();
        KitchenOrderItem it = items.findByIdAndBusinessId(itemId, bid).orElseThrow(() -> ApiException.notFound("Item not found"));
        KitchenOrder o = orders.lockOne(it.getOrderId(), bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if ("CANCELLED".equals(o.getStatus()) || o.getReversedAt() != null) throw ApiException.conflict("This order was cancelled");
        if (status == null || !NEXT.containsKey(status) && !"CANCELLED".equals(status)) throw ApiException.badRequest("Unknown status");
        if ("CANCELLED".equals(status)) throw ApiException.badRequest("Use Cancel item so a reason is recorded");
        // The same update sent twice (a second tap, a slow connection) changes nothing.
        if (status.equals(it.getStatus())) return view(o);
        if (!NEXT.getOrDefault(it.getStatus(), Set.of()).contains(status)) throw ApiException.conflict("That item cannot move from " + it.getStatus().toLowerCase() + " to " + status.toLowerCase());
        switch (status) {
            case "PREPARING" -> access.require(Permission.KITCHEN_STATUS_UPDATE);
            case "READY" -> access.require(Permission.KITCHEN_MARK_READY);
            default -> { if (!access.current().contains(Permission.SALES_CREATE)) access.require(Permission.KITCHEN_STATUS_UPDATE); }
        }
        Instant now = Instant.now();
        if ("PREPARING".equals(status)) {
            if (o.getAcceptedAt() == null) { o.setAcceptedAt(now); o.setAcceptedBy(Tenant.userId()); }
            checkAvailability(bid, o.getBranchId(), List.of(it));
            it.setStartedAt(now);
        }
        if ("READY".equals(status)) it.setReadyAt(now);
        if ("SERVED".equals(status)) it.setServedAt(now);
        it.setStatus(status);
        if ("READY".equals(status)) notify.publish(bid, Permission.SALES_CREATE, "ORDER_READY", it.getItemName() + " is ready", "Order #" + o.getNumber(), "/orders", null);
        return view(o);
    }

    /** Cancel one item. Before cooking starts anyone on the floor can; once it is being made a manager must, with a reason. */
    @Transactional
    public OrderDto cancelItem(UUID itemId, CancelRequest r) {
        UUID bid = gate();
        KitchenOrderItem it = items.findByIdAndBusinessId(itemId, bid).orElseThrow(() -> ApiException.notFound("Item not found"));
        KitchenOrder o = orders.lockOne(it.getOrderId(), bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if ("CANCELLED".equals(it.getStatus())) return view(o);
        requireOpen(o);
        cancelOne(bid, o, it, r);
        audit.record(bid, Tenant.userId(), "KITCHEN_ITEM_CANCELLED", "kitchen_order", o.getId(), "#" + o.getNumber() + " " + it.getItemName() + (blank(r.reason()) == null ? "" : ": " + r.reason().trim()));
        return view(o);
    }

    private void cancelOne(UUID bid, KitchenOrder o, KitchenOrderItem it, CancelRequest r) {
        String reason = blank(r.reason());
        switch (it.getStatus()) {
            case "NEW" -> { if (!access.current().contains(Permission.SALES_CREATE)) access.require(Permission.KITCHEN_STATUS_UPDATE); }
            case "PREPARING", "READY" -> {
                access.require(Permission.SALES_VOID);
                if (reason == null || reason.length() < 3) throw ApiException.badRequest("Say why " + it.getItemName() + " is being cancelled");
            }
            default -> throw ApiException.conflict(it.getItemName() + " was already served. Take payment, or reverse the order after payment.");
        }
        if (r.wasted() && !"NEW".equals(it.getStatus())) {
            access.require(Permission.STOCK_WASTAGE);
            // The food was already being made, so its ingredients are lost. The request id makes this record once.
            wastage.recordInternal(bid, Tenant.userId(), it.getVariantId(), it.getQuantity(), "PREP_WASTE", "Cancelled on order #" + o.getNumber() + ": " + reason,
                o.getBranchId(), "ko-item-" + it.getId(), "KO-" + o.getNumber());
            it.setWasted(true);
        }
        it.setStatus("CANCELLED");
        it.setCancelledAt(Instant.now());
        it.setCancelReason(reason);
    }

    @Transactional
    public OrderDto cancel(UUID orderId, CancelRequest r) {
        UUID bid = gate();
        KitchenOrder o = orders.lockOne(orderId, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if ("CANCELLED".equals(o.getStatus())) return view(o);
        requireOpen(o);
        List<KitchenOrderItem> live = items.findByOrderIdOrderByCreatedAt(o.getId()).stream().filter(i -> !"CANCELLED".equals(i.getStatus())).toList();
        for (KitchenOrderItem i : live) if ("SERVED".equals(i.getStatus())) throw ApiException.conflict("Some items were already served. Take payment instead of cancelling.");
        // Nothing was deducted from stock or recorded as money yet, so cancelling only closes the order (and records waste if asked).
        for (KitchenOrderItem i : live) cancelOne(bid, o, i, r);
        o.setStatus("CANCELLED");
        o.setCancelReason(blank(r.reason()));
        o.setCancelledBy(Tenant.userId());
        o.setClosedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "KITCHEN_ORDER_CANCELLED", "kitchen_order", o.getId(), "#" + o.getNumber() + (blank(r.reason()) == null ? "" : ": " + r.reason().trim()));
        return view(o);
    }

    // ---------- payment ----------
    /**
     * Turns the order into a real sale (stock, ledger, reports) in one transaction. Paying twice returns the same sale.
     * Payments may be split across methods; an unpaid part goes on the customer's account when a customer is chosen.
     */
    @Transactional
    public OrderDto pay(UUID orderId, PayRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        access.require(Permission.SALES_CREATE);
        KitchenOrder o = orders.lockOne(orderId, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if ("PAID".equals(o.getStatus())) return view(o);
        requireOpen(o);
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
                r.discount() == null ? BigDecimal.ZERO : r.discount(),
                r.customerId(),
                r.payments(),
                "Order #" + o.getNumber() + (o.getTableId() == null ? "" : " · table"),
                "ko-" + o.getId(),
                o.getBranchId(),
                null
        );
        SaleService.SaleDto sale = saleService.createInternal(req, "RESTAURANT", null, null, false, surcharge);

        // Ingredients used by chosen modifiers (for example extra cheese) come out of stock too, and count as cost.
        Map<UUID, BigDecimal> extraUse = modifierUse(live);
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
        o.setClosedAt(Instant.now());
        // Items still to be cooked stay on the kitchen board; paying does not mark them served.
        audit.record(bid, Tenant.userId(), "KITCHEN_ORDER_PAID", "kitchen_order", o.getId(), "#" + o.getNumber() + " sale #" + sale.saleNumber());
        return view(o);
    }

    private Map<UUID, BigDecimal> modifierUse(List<KitchenOrderItem> live) {
        Map<UUID, BigDecimal> extraUse = new LinkedHashMap<>();
        if (live.isEmpty()) return extraUse;
        Map<UUID, BigDecimal> qtyByItem = live.stream().collect(Collectors.toMap(KitchenOrderItem::getId, KitchenOrderItem::getQuantity));
        for (KitchenItemModifier km : itemMods.findByItemIdIn(live.stream().map(KitchenOrderItem::getId).toList())) {
            Modifier m = modifiers.findById(km.getModifierId()).orElse(null);
            if (m == null || m.getIngredientVariantId() == null) continue;
            extraUse.merge(m.getIngredientVariantId(), m.getIngredientQuantity().multiply(qtyByItem.getOrDefault(km.getItemId(), BigDecimal.ONE)), BigDecimal::add);
        }
        return extraUse;
    }

    /**
     * Approved reversal of a paid order (manager permission): the sale is voided through the normal sale path, which
     * returns the money. Ingredients go back to stock only for items the kitchen never started, when the manager asks.
     * Food that was already made is not put back; record it as wastage instead. Safe to repeat.
     */
    @Transactional
    public OrderDto reverse(UUID orderId, ReverseRequest r) {
        UUID bid = gate();
        access.require(Permission.SALES_VOID);
        KitchenOrder o = orders.lockOne(orderId, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (o.getReversedAt() != null) return view(o);
        if (!"PAID".equals(o.getStatus()) || o.getSaleId() == null) throw ApiException.conflict("Only a paid order can be reversed. Cancel it instead.");
        returns.voidSale(o.getSaleId(), new SaleReturnService.VoidRequest(r.reason().trim()));
        List<KitchenOrderItem> live = items.findByOrderIdOrderByCreatedAt(o.getId()).stream().filter(i -> !"CANCELLED".equals(i.getStatus())).toList();
        if (r.restock()) {
            List<KitchenOrderItem> untouched = live.stream().filter(i -> "NEW".equals(i.getStatus())).toList();
            Map<UUID, Recipe> made = recipes.madeToOrder(bid, untouched.stream().map(KitchenOrderItem::getVariantId).collect(Collectors.toSet()));
            for (KitchenOrderItem i : untouched) {
                Recipe rec = made.get(i.getVariantId());
                if (rec != null) recipes.restore(rec, i.getQuantity(), o.getBranchId(), "REVERSE-" + o.getNumber(), Tenant.userId());
            }
            Map<UUID, BigDecimal> extra = modifierUse(untouched);
            if (!extra.isEmpty()) {
                Map<UUID, ProductVariant> locked = variants.lockAll(bid, extra.keySet()).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
                for (Map.Entry<UUID, BigDecimal> e : extra.entrySet()) stock.apply(locked.get(e.getKey()), o.getBranchId(), MovementType.REFUND, e.getValue(), "Order reversed before it was made", "REVERSE-" + o.getNumber(), Tenant.userId(), true);
            }
        }
        Instant now = Instant.now();
        for (KitchenOrderItem i : live) if (!"SERVED".equals(i.getStatus())) { i.setStatus("CANCELLED"); i.setCancelledAt(now); i.setCancelReason("Order reversed"); }
        o.setReversedAt(now);
        o.setReverseReason(r.reason().trim());
        o.setReversedBy(Tenant.userId());
        audit.record(bid, Tenant.userId(), "KITCHEN_ORDER_REVERSED", "kitchen_order", o.getId(), "#" + o.getNumber() + ": " + r.reason().trim());
        return view(o);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
