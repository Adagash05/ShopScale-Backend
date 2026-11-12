package com.amsati.shopscale.store;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.branch.BranchStock;
import com.amsati.shopscale.branch.BranchStockRepository;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.ProductStatus;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.Category;
import com.amsati.shopscale.catalog.CategoryRepository;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.CounterService;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.common.PaymentMethod;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.notification.NotificationService;
import com.amsati.shopscale.restaurant.MenuService;
import com.amsati.shopscale.restaurant.Modifier;
import com.amsati.shopscale.restaurant.ModifierGroup;
import com.amsati.shopscale.restaurant.ModifierGroupRepository;
import com.amsati.shopscale.restaurant.ModifierRepository;
import com.amsati.shopscale.restaurant.ProductModifierGroup;
import com.amsati.shopscale.restaurant.ProductModifierGroupRepository;
import com.amsati.shopscale.payment.PaystackClient;
import com.amsati.shopscale.sales.SaleService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.SecretBox;
import com.amsati.shopscale.security.SystemContext;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.security.TokenHash;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Online orders end to end. Prices and totals are always computed here from the catalogue, never taken from the browser.
 * A paid order is only marked paid after the merchant's own Paystack confirms the amount, and that confirmation can come
 * from the customer's return (verify) or from the signed webhook; whichever arrives first wins and the other is a no-op.
 */
@Service
@RequiredArgsConstructor
public class StoreOrderService {
    private static final Logger log = LoggerFactory.getLogger(StoreOrderService.class);
    private static final BigDecimal MAX_QTY = new BigDecimal("1000");

    private final StoreSettingsRepository settingsRepo;
    private final StoreSettingsService settingsService;
    private final StoreOrderRepository orders;
    private final StoreOrderItemRepository orderItems;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;
    private final BranchStockRepository branchStock;
    private final BusinessRepository businesses;
    private final StockService stock;
    private final SubscriptionService subscriptions;
    private final PaystackClient paystack;
    private final SecretBox box;
    private final SaleService sales;
    private final SystemContext system;
    private final CounterService counters;
    private final NotificationService notifications;
    private final AuditService audit;
    private final PlatformTransactionManager txm;
    private final StoreReviewRepository reviews;
    private final ProductImageRepository images;
    private final StoreOrderItemOptionRepository itemOptions;
    private final ModifierGroupRepository modifierGroups;
    private final ModifierRepository modifierRepo;
    private final ProductModifierGroupRepository productGroups;
    private final MenuService menu;

    @Value("${app.frontend-url:http://localhost:5173}") private String frontendUrl;
    @Value("${app.storefront-url:}") private String storefrontUrl;
    @Value("${app.public-api-url:}") private String publicApiUrl;

    // ---------- DTOs ----------
    public record StoreInfo(String slug, String name, String title, String description, String phone, String whatsapp, boolean pickup, boolean delivery,
                            BigDecimal deliveryFee, String deliveryNote, boolean payOnline, boolean payLater,
                            String businessType, String subtype, String email, String address,
                            String logoUrl, String instagramUrl, String facebookUrl, String xUrl, String tiktokUrl, String openingHours) {}
    public record Option(UUID id, String name, BigDecimal priceDelta) {}
    public record OptionGroup(UUID id, String name, boolean required, int maxSelect, List<Option> options) {}
    public record StoreItem(UUID variantId, UUID productId, String name, String label, String description, BigDecimal price, boolean inStock,
                            BigDecimal available, String unit, boolean allowDecimal, String imageUrl, UUID categoryId, String categoryName, Instant createdAt,
                            BigDecimal compareAtPrice, List<String> images, String specs, Double ratingAvg, long ratingCount, List<OptionGroup> optionGroups) {}
    public record OrderLine(@NotNull UUID variantId, @NotNull @DecimalMin("0.001") BigDecimal quantity, @Size(max = 20) List<UUID> options) {}
    public record OrderRequest(@NotEmpty @Size(max = 60) @Valid List<OrderLine> items, @NotBlank @Size(max = 120) String customerName,
                               @NotBlank @Size(max = 30) String phone, @Email @Size(max = 150) String email, @NotBlank String deliveryMethod,
                               @Size(max = 300) String address, @Size(max = 300) String note, @NotBlank String paymentMode) {}
    public record Placed(String token, String status, String paymentMode, String authorizationUrl) {}
    public record OrderItemDto(UUID variantId, UUID productId, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}
    public record OrderDto(UUID id, long number, String token, String customerName, String phone, String email, String deliveryMethod, String address, String note,
                           String status, String paymentMode, String paymentStatus, BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal total,
                           Instant paidAt, UUID saleId, String attention, Instant createdAt, List<OrderItemDto> items, String storeName) {}
    public record CompleteRequest(PaymentMethod method) {}

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }
    private TransactionTemplate txNew() {
        TransactionTemplate t = new TransactionTemplate(txm);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    // ---------- public catalogue ----------
    record Live(Business business, StoreSettings settings) {}

    Live live(String slug) {
        Business b = businesses.findBySlug(slug == null ? "" : slug.toLowerCase()).orElseThrow(() -> ApiException.notFound("This store is not available"));
        StoreSettings s = settingsRepo.findById(b.getId()).filter(StoreSettings::isEnabled).orElseThrow(() -> ApiException.notFound("This store is not available"));
        if (!subscriptions.state(b.getId()).hasFeature("STOREFRONT")) throw ApiException.notFound("This store is not available");
        return new Live(b, s);
    }

    String logoUrl(String slug, StoreSettings s) {
        return s.getLogoType() == null ? null : (publicApiUrl == null ? "" : publicApiUrl.replaceAll("/+$", "")) + "/api/public/store/" + slug + "/logo?v=" + (s.getUpdatedAt() == null ? 0 : s.getUpdatedAt().toEpochMilli());
    }

    String slugOf(UUID businessId) { return businesses.findById(businessId).map(Business::getSlug).orElse(""); }

    public Optional<String> slugForDomain(String host) {
        return settingsRepo.findByCustomDomainIgnoreCase(host == null ? "" : host.toLowerCase()).filter(StoreSettings::isDomainVerified)
            .filter(StoreSettings::isEnabled).flatMap(s -> businesses.findById(s.getBusinessId())).map(Business::getSlug);
    }

    public StoreInfo info(String slug) {
        Live l = live(slug);
        StoreSettings s = l.settings();
        return new StoreInfo(l.business().getSlug(), l.business().getName(), s.getTitle() == null ? l.business().getName() : s.getTitle(), s.getDescription(), s.getPhone(), s.getWhatsapp(),
            s.isPickupEnabled(), s.isDeliveryEnabled(), s.getDeliveryFee(), s.getDeliveryNote(), s.getPaystackSecretEnc() != null, s.isPayLaterEnabled(),
            l.business().getBusinessType() == null ? null : l.business().getBusinessType().name(), l.business().getSubtype(), l.business().getEmail(), l.business().getAddress(),
            logoUrl(l.business().getSlug(), s), s.getInstagramUrl(), s.getFacebookUrl(), s.getXUrl(), s.getTiktokUrl(), s.getOpeningHours());
    }

    public List<StoreItem> catalogue(String slug, String q) {
        Live l = live(slug);
        UUID bid = l.business().getId();
        UUID branch = stock.resolveBranch(bid, l.settings().getBranchId());
        Map<UUID, BigDecimal> here = new HashMap<>();
        for (BranchStock s : branchStock.forBranch(bid, branch)) here.put(s.getId().getVariantId(), s.getQuantity());
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase().replace("%", "") + "%";
        // Category names of THIS business only (tenant-scoped query), so no other merchant's data can appear.
        Map<UUID, String> categoryNames = new HashMap<>();
        for (Category c : categories.findByBusinessIdOrderByNameAsc(bid)) categoryNames.put(c.getId(), c.getName());
        List<ProductVariant> rows = new ArrayList<>();
        for (ProductVariant v : variants.sellable(bid, ProductStatus.ACTIVE, like, PageRequest.of(0, 500))) {
            String kind = v.getProduct().getKind();
            if ("GOODS".equals(kind) || "FINISHED".equals(kind)) rows.add(v);
        }
        Set<UUID> productIds = new HashSet<>();
        for (ProductVariant v : rows) productIds.add(v.getProduct().getId());
        String api = publicApiUrl == null ? "" : publicApiUrl.replaceAll("/+$", "");
        Map<UUID, List<String>> gallery = new HashMap<>();
        for (ProductImage im : images.findByBusinessIdAndGalleryTrueOrderByPositionAscCreatedAtAsc(bid))
            gallery.computeIfAbsent(im.getProductId(), k -> new ArrayList<>()).add(api + "/api/public/images/" + im.getId());
        Map<UUID, Object[]> rating = new HashMap<>();
        for (Object[] r : reviews.summary(bid)) rating.put((UUID) r[0], r);
        Map<UUID, List<OptionGroup>> optionGroups = optionGroups(bid, productIds);
        List<StoreItem> out = new ArrayList<>();
        for (ProductVariant v : rows) {
            UUID pid = v.getProduct().getId();
            BigDecimal qty = here.getOrDefault(v.getId(), BigDecimal.ZERO);
            boolean ok = qty.signum() > 0 || l.business().isAllowNegativeStock();
            BigDecimal was = v.getCompareAtPrice() != null && v.getCompareAtPrice().compareTo(v.getPrice()) > 0 ? v.getCompareAtPrice() : null;
            Object[] rt = rating.get(pid);
            out.add(new StoreItem(v.getId(), pid, v.getProduct().getName(), v.getLabel(), v.getProduct().getDescription(), v.getPrice(), ok,
                qty.max(BigDecimal.ZERO), v.getUnit(), v.isAllowDecimal(), v.getProduct().getImageUrl(),
                v.getProduct().getCategoryId(), v.getProduct().getCategoryId() == null ? null : categoryNames.get(v.getProduct().getCategoryId()), v.getProduct().getCreatedAt(),
                was, gallery.getOrDefault(pid, List.of()), v.getProduct().getSpecs(),
                rt == null ? null : Math.round(((Number) rt[2]).doubleValue() * 10) / 10.0, rt == null ? 0 : ((Number) rt[1]).longValue(), optionGroups.getOrDefault(pid, List.of())));
        }
        return out;
    }

    /** Menu option groups per product: only this business's active groups with at least one active option. */
    private Map<UUID, List<OptionGroup>> optionGroups(UUID bid, Set<UUID> productIds) {
        Map<UUID, List<OptionGroup>> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        List<ProductModifierGroup> links = productGroups.findByProductIdIn(productIds);
        if (links.isEmpty()) return out;
        Map<UUID, ModifierGroup> gs = new HashMap<>();
        for (ModifierGroup g : modifierGroups.findAllById(links.stream().map(ProductModifierGroup::getGroupId).collect(java.util.stream.Collectors.toSet())))
            if (g.getBusinessId().equals(bid) && g.isActive()) gs.put(g.getId(), g);
        if (gs.isEmpty()) return out;
        Map<UUID, List<Modifier>> ms = new HashMap<>();
        for (Modifier m : modifierRepo.findByGroupIdIn(gs.keySet())) if (m.isActive() && m.getBusinessId().equals(bid)) ms.computeIfAbsent(m.getGroupId(), k -> new ArrayList<>()).add(m);
        for (ProductModifierGroup link : links) {
            ModifierGroup g = gs.get(link.getGroupId());
            List<Modifier> mm = g == null ? null : ms.get(g.getId());
            if (mm == null || mm.isEmpty()) continue;
            mm.sort(Comparator.comparing(Modifier::getName));
            out.computeIfAbsent(link.getProductId(), k -> new ArrayList<>()).add(new OptionGroup(g.getId(), g.getName(), g.isRequired(), g.getMaxSelect(),
                mm.stream().map(m -> new Option(m.getId(), m.getName(), Money.scale(m.getPriceDelta()))).toList()));
        }
        return out;
    }

    // ---------- placing an order ----------
    public Placed place(String slug, OrderRequest r) {
        Live l = live(slug);
        UUID bid = l.business().getId();
        StoreSettings s = l.settings();
        String method = r.deliveryMethod().toUpperCase();
        String mode = r.paymentMode().toUpperCase();
        if (!method.equals("PICKUP") && !method.equals("DELIVERY")) throw ApiException.badRequest("Choose pickup or delivery");
        if (method.equals("PICKUP") && !s.isPickupEnabled()) throw ApiException.badRequest("This store does not offer pickup");
        if (method.equals("DELIVERY") && !s.isDeliveryEnabled()) throw ApiException.badRequest("This store does not offer delivery");
        if (method.equals("DELIVERY") && (r.address() == null || r.address().isBlank())) throw ApiException.badRequest("Enter your delivery address");
        if (mode.equals("ONLINE")) {
            if (s.getPaystackSecretEnc() == null) throw ApiException.badRequest("Online payment is not available for this store");
            if (r.email() == null || r.email().isBlank()) throw ApiException.badRequest("Enter your email address to pay online");
        } else if (mode.equals("PAY_LATER")) {
            if (!s.isPayLaterEnabled()) throw ApiException.badRequest("Pay on pickup or delivery is not available for this store");
        } else throw ApiException.badRequest("Choose how you will pay");

        // Merge identical lines (same item and same options), then price everything from the catalogue.
        record Key(UUID variantId, List<UUID> options) {}
        Map<Key, BigDecimal> wanted = new LinkedHashMap<>();
        Map<UUID, BigDecimal> perVariant = new LinkedHashMap<>();
        for (OrderLine ln : r.items()) {
            List<UUID> opts = ln.options() == null ? List.of() : ln.options().stream().distinct().sorted().toList();
            wanted.merge(new Key(ln.variantId(), opts), ln.quantity(), BigDecimal::add);
            perVariant.merge(ln.variantId(), ln.quantity(), BigDecimal::add);
        }
        UUID branch = stock.resolveBranch(bid, s.getBranchId());
        Map<UUID, BigDecimal> here = new HashMap<>();
        for (BranchStock bs : branchStock.forBranch(bid, branch)) here.put(bs.getId().getVariantId(), bs.getQuantity());
        Map<UUID, ProductVariant> byId = new HashMap<>();
        for (ProductVariant v : variants.findAllById(perVariant.keySet())) byId.put(v.getId(), v);
        if (byId.size() != perVariant.size()) throw ApiException.badRequest("An item in your cart is no longer available");
        for (ProductVariant v : byId.values()) {
            if (!v.getBusinessId().equals(bid) || !v.isActive() || v.getProduct().getStatus() != ProductStatus.ACTIVE
                || !("GOODS".equals(v.getProduct().getKind()) || "FINISHED".equals(v.getProduct().getKind())))
                throw ApiException.badRequest(v.getProduct().getName() + " is no longer available");
            BigDecimal qty = perVariant.get(v.getId());
            if (qty.compareTo(MAX_QTY) > 0) throw ApiException.badRequest("That quantity is too large");
            if (!v.isAllowDecimal() && qty.stripTrailingZeros().scale() > 0) throw ApiException.badRequest(v.getProduct().getName() + " is sold in whole units");
            if (!l.business().isAllowNegativeStock() && here.getOrDefault(v.getId(), BigDecimal.ZERO).compareTo(qty) < 0)
                throw ApiException.conflict("Only " + here.getOrDefault(v.getId(), BigDecimal.ZERO).stripTrailingZeros().toPlainString() + " of " + v.getProduct().getName() + " left");
        }
        // Menu options: each must belong to an active group attached to THIS product of THIS business; group limits are enforced here, not in the browser.
        Set<UUID> optionIds = new HashSet<>();
        for (Key k : wanted.keySet()) optionIds.addAll(k.options());
        Map<UUID, Modifier> mods = new HashMap<>();
        for (Modifier m : modifierRepo.findAllById(optionIds)) mods.put(m.getId(), m);
        Set<UUID> productIds = new HashSet<>();
        for (ProductVariant v : byId.values()) productIds.add(v.getProduct().getId());
        Map<UUID, OptionGroup> allowedByGroup = new HashMap<>();
        Map<UUID, Set<UUID>> groupsOfProduct = new HashMap<>();
        for (Map.Entry<UUID, List<OptionGroup>> e : optionGroups(bid, productIds).entrySet())
            for (OptionGroup g : e.getValue()) { allowedByGroup.put(g.id(), g); groupsOfProduct.computeIfAbsent(e.getKey(), x -> new HashSet<>()).add(g.id()); }
        record Built(StoreOrderItem item, List<Modifier> opts) {}
        List<Built> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Map.Entry<Key, BigDecimal> e : wanted.entrySet()) {
            ProductVariant v = byId.get(e.getKey().variantId());
            UUID pid = v.getProduct().getId();
            Map<UUID, Integer> perGroup = new HashMap<>();
            List<Modifier> chosen = new ArrayList<>();
            BigDecimal extra = BigDecimal.ZERO;
            for (UUID oid : e.getKey().options()) {
                Modifier m = mods.get(oid);
                if (m == null || !m.getBusinessId().equals(bid) || !m.isActive() || !groupsOfProduct.getOrDefault(pid, Set.of()).contains(m.getGroupId()))
                    throw ApiException.badRequest("An option you chose for " + v.getProduct().getName() + " is no longer available");
                perGroup.merge(m.getGroupId(), 1, Integer::sum);
                chosen.add(m);
                extra = extra.add(Money.scale(m.getPriceDelta()));
            }
            for (UUID gid : groupsOfProduct.getOrDefault(pid, Set.of())) {
                OptionGroup g = allowedByGroup.get(gid);
                int n = perGroup.getOrDefault(gid, 0);
                if (g.required() && n == 0) throw ApiException.badRequest("Choose " + g.name() + " for " + v.getProduct().getName());
                if (n > g.maxSelect()) throw ApiException.badRequest("You can choose at most " + g.maxSelect() + " for " + g.name());
            }
            chosen.sort(Comparator.comparing(Modifier::getName));
            BigDecimal unit = Money.scale(v.getPrice().add(extra));
            BigDecimal qty = e.getValue();
            StoreOrderItem it = new StoreOrderItem();
            it.setBusinessId(bid);
            it.setVariantId(v.getId());
            it.setItemName(v.getProduct().getName() + (v.getLabel() == null || v.getLabel().isBlank() ? "" : " - " + v.getLabel())
                + (chosen.isEmpty() ? "" : " + " + String.join(", ", chosen.stream().map(Modifier::getName).toList())));
            it.setQuantity(qty);
            it.setUnitPrice(unit);
            it.setOptionsTotal(Money.scale(extra));
            it.setLineTotal(Money.scale(unit.multiply(qty)));
            subtotal = subtotal.add(it.getLineTotal());
            lines.add(new Built(it, chosen));
        }
        BigDecimal fee = method.equals("DELIVERY") ? Money.scale(s.getDeliveryFee()) : BigDecimal.ZERO;
        BigDecimal total = subtotal.add(fee);
        if (total.signum() <= 0) throw ApiException.badRequest("Your order total must be above zero");

        final BigDecimal fSub = subtotal;
        StoreOrder o = tx().execute(st -> {
            StoreOrder n = new StoreOrder();
            n.setBusinessId(bid);
            n.setNumber(counters.next(bid, "store_order"));
            n.setPublicToken(TokenHash.newToken());
            n.setCustomerName(r.customerName().trim());
            n.setPhone(r.phone().trim());
            n.setEmail(r.email() == null || r.email().isBlank() ? null : r.email().trim());
            n.setDeliveryMethod(method);
            n.setAddress(method.equals("DELIVERY") ? r.address().trim() : null);
            n.setNote(r.note() == null || r.note().isBlank() ? null : r.note().trim());
            n.setPaymentMode(mode);
            n.setStatus(mode.equals("ONLINE") ? "PENDING_PAYMENT" : "NEW");
            n.setSubtotal(fSub);
            n.setDeliveryFee(fee);
            n.setTotal(total);
            n.setBranchId(branch);
            if (mode.equals("ONLINE")) n.setPaystackReference("SSO-" + TokenHash.newToken().substring(0, 24));
            n = orders.save(n);
            for (Built b : lines) {
                b.item().setOrderId(n.getId());
                StoreOrderItem saved = orderItems.save(b.item());
                for (Modifier m : b.opts()) {
                    StoreOrderItemOption oo = new StoreOrderItemOption();
                    oo.setItemId(saved.getId());
                    oo.setModifierId(m.getId());
                    oo.setName(m.getName());
                    oo.setPriceDelta(Money.scale(m.getPriceDelta()));
                    itemOptions.save(oo);
                }
            }
            if (mode.equals("PAY_LATER")) announce(n);
            return n;
        });

        String url = null;
        if (mode.equals("ONLINE")) {
            try {
                String secret = box.decrypt(s.getPaystackSecretEnc());
                String callback = storeBase() + "/" + slug.toLowerCase() + "/order/" + o.getPublicToken();
                url = paystack.initialize(secret, o.getEmail(), PaystackClient.toKobo(total), o.getPaystackReference(), callback,
                    Map.of("store_order", o.getId().toString(), "order_number", o.getNumber())).authorizationUrl();
            } catch (RuntimeException e) {
                log.warn("Paystack initialise failed for store order {}: {}", o.getId(), e.getMessage());
                tx().executeWithoutResult(st -> orders.findById(o.getId()).ifPresent(x -> { x.setStatus("CANCELLED"); x.setAttention("Could not start payment"); }));
                throw ApiException.conflict("We could not start the payment. Please try again.");
            }
        }
        return new Placed(o.getPublicToken(), o.getStatus(), mode, url);
    }

    private String storeBase() { return storefrontUrl == null || storefrontUrl.isBlank() ? frontendUrl.replaceAll("/+$", "") + "/s" : storefrontUrl.replaceAll("/+$", ""); }

    private void announce(StoreOrder o) {
        notifications.publish(o.getBusinessId(), Permission.STORE_ORDERS_MANAGE, "STORE_ORDER", "New online order #" + o.getNumber(),
            o.getCustomerName() + " - " + Money.scale(o.getTotal()).toPlainString() + (o.getPaymentStatus().equals("PAID") ? " (paid online)" : " (pay later)"),
            "/more/store-orders", "store-order-" + o.getId());
    }

    // ---------- customer-facing status, verify, webhook ----------
    public OrderDto status(String token) {
        StoreOrder o = orders.findByPublicToken(token).orElseThrow(() -> ApiException.notFound("Order not found"));
        return toDto(o, true);
    }

    /** The customer's return from Paystack. Asks Paystack itself; the browser's claim is never trusted. */
    public OrderDto verify(String token) {
        StoreOrder o = orders.findByPublicToken(token).orElseThrow(() -> ApiException.notFound("Order not found"));
        if ("PENDING_PAYMENT".equals(o.getStatus()) && o.getPaystackReference() != null) {
            StoreSettings s = settingsRepo.findById(o.getBusinessId()).orElse(null);
            if (s != null && s.getPaystackSecretEnc() != null) {
                try {
                    var v = paystack.verify(box.decrypt(s.getPaystackSecretEnc()), o.getPaystackReference());
                    settle(o.getId(), v);
                } catch (RuntimeException e) {
                    log.warn("Verify failed for store order {}: {}", o.getId(), e.getMessage());
                }
            }
        }
        return toDto(orders.findById(o.getId()).orElse(o), true);
    }

    public void webhook(String slug, String rawBody, String signature) {
        Business b = businesses.findBySlug(slug == null ? "" : slug.toLowerCase()).orElseThrow(() -> ApiException.notFound("Unknown store"));
        StoreSettings s = settingsRepo.findById(b.getId()).orElseThrow(() -> ApiException.notFound("Unknown store"));
        if (s.getPaystackSecretEnc() == null) throw ApiException.badRequest("No payment connection");
        String secret = box.decrypt(s.getPaystackSecretEnc());
        if (!paystack.validSignature(secret, rawBody, signature)) throw ApiException.unauthorized("Bad signature");
        var root = paystack.parse(rawBody);
        if (!"charge.success".equals(root.path("event").asText())) return;
        var data = root.path("data");
        String ref = data.path("reference").asText("");
        orders.findByPaystackReference(ref).filter(o -> o.getBusinessId().equals(b.getId())).ifPresent(o -> {
            // Do not trust the body: ask Paystack again with the same secret.
            settle(o.getId(), paystack.verify(secret, ref));
        });
    }

    /** Idempotent: marks the order paid once the amount and currency match, then creates the sale. */
    private void settle(UUID orderId, PaystackClient.Verification v) {
        Boolean newlyPaid = tx().execute(st -> {
            StoreOrder o = orders.lockById(orderId).orElseThrow();
            if ("PAID".equals(o.getPaymentStatus())) return false;
            if (!v.success()) {
                if ("failed".equalsIgnoreCase(v.status()) || "abandoned".equalsIgnoreCase(v.status())) o.setPaymentStatus("FAILED");
                return false;
            }
            if (v.amountKobo() != PaystackClient.toKobo(o.getTotal()) || !"NGN".equalsIgnoreCase(v.currency()) || !Objects.equals(v.reference(), o.getPaystackReference())) {
                o.setAttention("Payment did not match the order amount. Check Paystack before fulfilling.");
                log.warn("Store order {} payment mismatch: paid {} expected {}", o.getId(), v.amountKobo(), o.getTotal());
                return false;
            }
            o.setPaymentStatus("PAID");
            o.setPaidAt(Instant.now());
            if ("PENDING_PAYMENT".equals(o.getStatus()) || "CANCELLED".equals(o.getStatus())) {
                if ("CANCELLED".equals(o.getStatus())) o.setAttention("Customer paid after this order was cancelled. Refund in Paystack.");
                else o.setStatus("NEW");
            }
            o.setUpdatedAt(Instant.now());
            return true;
        });
        if (Boolean.TRUE.equals(newlyPaid)) {
            StoreOrder o = orders.findById(orderId).orElseThrow();
            if (!"CANCELLED".equals(o.getStatus())) {
                announce(o);
                recordSale(o, null);
            }
        }
    }

    /** Creates the sale in its own transaction so a stock problem cannot undo the fact that the customer paid. */
    private void recordSale(StoreOrder o, PaymentMethod method) {
        UUID oid = o.getId();
        try {
            txNew().executeWithoutResult(st -> {
                StoreOrder cur = orders.lockById(oid).orElseThrow();
                if (cur.getSaleId() != null) return;
                List<StoreOrderItem> stored = orderItems.findByOrderId(oid);
                List<SaleService.ItemRequest> its = stored.stream().map(i -> new SaleService.ItemRequest(i.getVariantId(), i.getQuantity(), null)).toList();
                // Paid options are a server-computed surcharge on top of the catalogue price, like the delivery fee; their ingredients leave stock.
                BigDecimal optionsSurcharge = BigDecimal.ZERO;
                Map<UUID, BigDecimal> ingredientUse = new LinkedHashMap<>();
                Map<UUID, StoreOrderItem> storedById = new HashMap<>();
                for (StoreOrderItem i : stored) {
                    storedById.put(i.getId(), i);
                    optionsSurcharge = optionsSurcharge.add(i.getOptionsTotal().multiply(i.getQuantity()));
                }
                for (StoreOrderItemOption oo : itemOptions.findByItemIdIn(storedById.keySet())) {
                    Modifier m = modifierRepo.findById(oo.getModifierId()).orElse(null);
                    if (m == null || m.getIngredientVariantId() == null || m.getIngredientQuantity() == null) continue;
                    ingredientUse.merge(m.getIngredientVariantId(), m.getIngredientQuantity().multiply(storedById.get(oo.getItemId()).getQuantity()), BigDecimal::add);
                }
                final BigDecimal surcharge = Money.scale(cur.getDeliveryFee().add(optionsSurcharge));
                boolean online = "PAID".equals(cur.getPaymentStatus());
                List<SaleService.PayRequest> pays = online ? List.of() : List.of(new SaleService.PayRequest(method, cur.getTotal()));
                var req = new SaleService.SaleRequest(
                        its,
                        BigDecimal.ZERO,
                        null,
                        pays,
                        "Online order #" + cur.getNumber() + " - " + cur.getCustomerName(),
                        "store-" + oid,
                        cur.getBranchId(),
                        online ? null : cur.getTotal()
                );                var sale = system.asBusiness(cur.getBusinessId(), () -> {
                    var made = sales.createInternal(req, "ONLINE", online ? cur.getTotal() : null, online ? PaymentMethod.CARD : null, true, surcharge);
                    menu.applyOptionIngredients(cur.getBusinessId(), cur.getBranchId(), ingredientUse, made.id(), made.saleNumber());
                    return made;
                });
                cur.setSaleId(sale.id());
                cur.setAttention(null);
            });
        } catch (RuntimeException e) {
            log.warn("Could not create the sale for store order {}: {}", oid, e.getMessage());
            tx().executeWithoutResult(st -> orders.findById(oid).ifPresent(x -> {
                x.setAttention(("PAID".equals(x.getPaymentStatus()) ? "PAID but the sale could not be recorded: " : "Sale could not be recorded: ") + String.valueOf(e.getMessage()));
                x.setUpdatedAt(Instant.now());
            }));
            if (method != null) throw e instanceof ApiException ? e : ApiException.conflict("The sale could not be recorded: " + e.getMessage());
        }
    }

    // ---------- merchant order management ----------
    public List<OrderDto> list(String status) {
        UUID bid = Tenant.businessId();
        List<StoreOrder> rows = status == null || status.isBlank() ? orders.findTop100ByBusinessIdOrderByCreatedAtDesc(bid)
            : orders.findTop100ByBusinessIdAndStatusOrderByCreatedAtDesc(bid, status.toUpperCase());
        return rows.stream().map(o -> toDto(o, false)).toList();
    }

    public OrderDto get(UUID id) {
        return toDto(orders.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Order not found")), false);
    }

    public Map<String, Long> counts() {
        UUID bid = Tenant.businessId();
        Map<String, Long> m = new LinkedHashMap<>();
        for (String s : List.of("NEW", "CONFIRMED", "READY")) m.put(s, orders.countByBusinessIdAndStatus(bid, s));
        return m;
    }

    private StoreOrder mine(UUID id) {
        UUID bid = Tenant.businessId();
        orders.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        return orders.lockById(id).orElseThrow();
    }

    public OrderDto advance(UUID id, String to) {
        UUID bid = Tenant.businessId();
        String target = to == null ? "" : to.toUpperCase();
        OrderDto out = tx().execute(st -> {
            StoreOrder o = mine(id);
            switch (target) {
                case "CONFIRMED" -> require(o, "NEW");
                case "READY" -> require(o, "CONFIRMED");
                default -> throw ApiException.badRequest("Use complete or cancel for that change");
            }
            o.setStatus(target);
            o.setUpdatedAt(Instant.now());
            audit.record(bid, Tenant.userId(), "STORE_ORDER_" + target, "store_order", o.getId(), "#" + o.getNumber());
            return toDto(o, false);
        });
        return out;
    }

    /** Hands the order over. Pay-later orders must say how they were paid, and the sale is recorded at this moment. */
    public OrderDto complete(UUID id, CompleteRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireWritable(bid);
        StoreOrder cur = orders.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Order not found"));
        boolean online = "PAID".equals(cur.getPaymentStatus());
        if (!online && (r == null || r.method() == null)) throw ApiException.badRequest("Choose how the customer paid");
        if (!List.of("NEW", "CONFIRMED", "READY").contains(cur.getStatus())) throw ApiException.conflict("This order cannot be completed");
        if (cur.getSaleId() == null) recordSale(cur, online ? null : r.method());
        return tx().execute(st -> {
            StoreOrder o = mine(id);
            if (o.getSaleId() == null) throw ApiException.conflict(o.getAttention() == null ? "The sale could not be recorded" : o.getAttention());
            o.setStatus("COMPLETED");
            o.setUpdatedAt(Instant.now());
            audit.record(bid, Tenant.userId(), "STORE_ORDER_COMPLETED", "store_order", o.getId(), "#" + o.getNumber());
            return toDto(o, false);
        });
    }

    public OrderDto cancel(UUID id) {
        UUID bid = Tenant.businessId();
        return tx().execute(st -> {
            StoreOrder o = mine(id);
            if ("COMPLETED".equals(o.getStatus()) || "CANCELLED".equals(o.getStatus())) throw ApiException.conflict("This order is already closed");
            if (o.getSaleId() != null) throw ApiException.conflict("A sale was already recorded. Refund it from Sales instead.");
            o.setStatus("CANCELLED");
            if ("PAID".equals(o.getPaymentStatus())) o.setAttention("Customer paid online. Refund them in your Paystack dashboard.");
            o.setUpdatedAt(Instant.now());
            audit.record(bid, Tenant.userId(), "STORE_ORDER_CANCELLED", "store_order", o.getId(), "#" + o.getNumber());
            return toDto(o, false);
        });
    }

    public OrderDto clearAttention(UUID id) {
        return tx().execute(st -> { StoreOrder o = mine(id); o.setAttention(null); return toDto(o, false); });
    }

    private static void require(StoreOrder o, String status) {
        if (!status.equals(o.getStatus())) throw ApiException.conflict("This order is " + o.getStatus().toLowerCase().replace('_', ' ') + " and cannot move that way");
    }

    private OrderDto toDto(StoreOrder o, boolean publicView) {
        List<StoreOrderItem> stored = orderItems.findByOrderId(o.getId());
        Map<UUID, UUID> productOf = new HashMap<>();
        if (publicView) for (ProductVariant v : variants.findAllById(stored.stream().map(StoreOrderItem::getVariantId).toList())) productOf.put(v.getId(), v.getProduct().getId());
        List<OrderItemDto> its = stored.stream().map(i -> new OrderItemDto(i.getVariantId(), productOf.get(i.getVariantId()), i.getItemName(), i.getQuantity(), i.getUnitPrice(), i.getLineTotal())).toList();
        String name = businesses.findById(o.getBusinessId()).map(Business::getName).orElse("");
        return new OrderDto(publicView ? null : o.getId(), o.getNumber(), publicView ? o.getPublicToken() : null, o.getCustomerName(), o.getPhone(), o.getEmail(), o.getDeliveryMethod(),
            o.getAddress(), o.getNote(), o.getStatus(), o.getPaymentMode(), o.getPaymentStatus(), o.getSubtotal(), o.getDeliveryFee(), o.getTotal(), o.getPaidAt(),
            publicView ? null : o.getSaleId(), publicView ? null : o.getAttention(), o.getCreatedAt(), its, name);
    }
}
