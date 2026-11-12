package com.amsati.shopscale.store;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.catalog.Product;
import com.amsati.shopscale.catalog.ProductRepository;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.notification.NotificationService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storefront extras: logo, social links and hours, sale prices, specifications, gallery, verified-purchase reviews,
 * newsletter, catering enquiries and order lookup. Public calls are keyed by the store slug and never accept a tenant id.
 */
@Service
@RequiredArgsConstructor
public class StoreExtrasService {
    static final int MAX_LOGO_BYTES = 300 * 1024;
    static final int MAX_GALLERY = 8;

    private final StoreOrderService orderService;
    private final StoreSettingsRepository settingsRepo;
    private final StoreLogoRepository logos;
    private final StoreOrderRepository orders;
    private final StoreOrderItemRepository orderItems;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final StoreReviewRepository reviews;
    private final StoreSubscriberRepository subscribers;
    private final StoreEnquiryRepository enquiries;
    private final NotificationService notifications;
    private final AuditService audit;

    @Value("${app.public-api-url:}") private String publicApiUrl;

    private String api() { return publicApiUrl == null ? "" : publicApiUrl.replaceAll("/+$", ""); }

    // ---------- records ----------
    public record ProfileRequest(@Size(max = 200) String instagramUrl, @Size(max = 200) String facebookUrl, @Size(max = 200) String xUrl,
                                 @Size(max = 200) String tiktokUrl, @Size(max = 600) String openingHours) {}
    public record ProfileDto(String instagramUrl, String facebookUrl, String xUrl, String tiktokUrl, String openingHours, boolean hasLogo, String logoUrl) {}
    public record VariantPrice(UUID variantId, String label, BigDecimal price, BigDecimal compareAtPrice) {}
    public record GalleryImage(UUID id, String url) {}
    public record PresentationDto(UUID productId, String name, String specs, List<VariantPrice> variants, List<GalleryImage> gallery) {}
    public record PresentationRequest(@Size(max = 2000) String specs, Map<UUID, BigDecimal> compareAt) {}
    public record ReviewRequest(@NotBlank String token, @NotNull UUID productId, @Min(1) @Max(5) int rating, @NotBlank @Size(max = 1000) String body) {}
    public record ReviewDto(UUID id, String author, int rating, String body, Instant createdAt, String productName) {}
    public record ReviewAdminDto(UUID id, String product, String author, int rating, String body, String status, Instant createdAt) {}
    public record SubscribeRequest(@NotBlank @Email @Size(max = 150) String email) {}
    public record EnquiryRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 30) String phone, @Email @Size(max = 150) String email,
                                 LocalDate eventDate, @Min(1) @Max(100000) Integer guests, @NotBlank @Size(max = 1500) String message) {}
    public record EnquiryDto(UUID id, String name, String phone, String email, LocalDate eventDate, Integer guests, String message, String status, Instant createdAt) {}
    public record SubscriberDto(String email, Instant createdAt) {}
    public record LookupRequest(@NotBlank @Size(max = 30) String phone, @NotNull Long number) {}

    // ---------- logo, social links, hours (merchant) ----------
    @Transactional
    public ProfileDto profile() {
        UUID bid = Tenant.businessId();
        return profileDto(bid, load(bid));
    }

    private StoreSettings load(UUID bid) {
        return settingsRepo.findById(bid).orElseGet(() -> { StoreSettings s = new StoreSettings(); s.setBusinessId(bid); return settingsRepo.save(s); });
    }

    private ProfileDto profileDto(UUID bid, StoreSettings s) {
        String slug = orderService.slugOf(bid);
        return new ProfileDto(s.getInstagramUrl(), s.getFacebookUrl(), s.getXUrl(), s.getTiktokUrl(), s.getOpeningHours(), s.getLogoType() != null, logoUrl(slug, s));
    }

    String logoUrl(String slug, StoreSettings s) {
        return orderService.logoUrl(slug, s);
    }

    @Transactional
    public ProfileDto saveProfile(ProfileRequest r) {
        UUID bid = Tenant.businessId();
        StoreSettings s = load(bid);
        s.setInstagramUrl(social(r.instagramUrl(), "instagram.com"));
        s.setFacebookUrl(social(r.facebookUrl(), "facebook.com", "fb.com", "fb.me"));
        s.setXUrl(social(r.xUrl(), "x.com", "twitter.com"));
        s.setTiktokUrl(social(r.tiktokUrl(), "tiktok.com"));
        s.setOpeningHours(blank(r.openingHours()));
        s.setUpdatedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "STORE_PROFILE_SAVED", "store", bid, null);
        return profileDto(bid, s);
    }

    /** Accepts only https links on the network's own domain, so a store cannot publish a link to somewhere else. */
    static String social(String v, String... hosts) {
        String x = blank(v);
        if (x == null) return null;
        if (!x.matches("(?i)^https?://.*")) x = "https://" + x;
        java.net.URI u;
        try { u = java.net.URI.create(x); } catch (IllegalArgumentException e) { throw ApiException.badRequest("That link is not valid: " + v); }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase().replaceFirst("^www\\.", "");
        for (String h : hosts) if (host.equals(h) || host.endsWith("." + h)) return "https://" + u.getHost() + (u.getRawPath() == null ? "" : u.getRawPath());
        throw ApiException.badRequest("Use a link on " + hosts[0]);
    }

    @Transactional
    public ProfileDto saveLogo(byte[] bytes) {
        UUID bid = Tenant.businessId();
        if (bytes == null || bytes.length == 0) throw ApiException.badRequest("Choose an image");
        if (bytes.length > MAX_LOGO_BYTES) throw ApiException.badRequest("The logo is too large. Use one under 300 KB.");
        String type = ProductImageController.sniff(bytes);
        if (type == null) throw ApiException.badRequest("Use a JPEG, PNG or WebP image");
        StoreSettings s = load(bid);
        StoreLogo lg = logos.findById(bid).orElseGet(() -> { StoreLogo n = new StoreLogo(); n.setBusinessId(bid); return n; });
        lg.setData(bytes);
        logos.save(lg);
        s.setLogoType(type);
        s.setUpdatedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "STORE_LOGO_SAVED", "store", bid, null);
        return profileDto(bid, s);
    }

    @Transactional
    public ProfileDto removeLogo() {
        UUID bid = Tenant.businessId();
        StoreSettings s = load(bid);
        logos.deleteById(bid);
        s.setLogoType(null);
        s.setUpdatedAt(Instant.now());
        return profileDto(bid, s);
    }

    /** Public: the logo bytes of an open store. */
    @Transactional(readOnly = true)
    public Optional<LogoFile> logoOf(String slug) {
        var live = orderService.live(slug);
        String type = live.settings().getLogoType();
        if (type == null) return Optional.empty();
        return logos.findById(live.business().getId()).map(l -> new LogoFile(type, l.getData()));
    }

    public record LogoFile(String type, byte[] data) {}

    // ---------- sale prices, specs, gallery (merchant) ----------
    private Product mine(UUID productId) {
        UUID bid = Tenant.businessId();
        return products.findById(productId).filter(p -> p.getBusinessId().equals(bid)).orElseThrow(() -> ApiException.notFound("Product not found"));
    }

    @Transactional(readOnly = true)
    public PresentationDto presentation(UUID productId) {
        Product p = mine(productId);
        return presentationDto(p);
    }

    private PresentationDto presentationDto(Product p) {
        List<VariantPrice> vs = variantsOf(p);
        List<GalleryImage> g = galleryOf(p.getBusinessId(), p.getId()).stream().map(i -> new GalleryImage(i.getId(), api() + "/api/public/images/" + i.getId())).toList();
        return new PresentationDto(p.getId(), p.getName(), p.getSpecs(), vs, g);
    }

    private List<VariantPrice> variantsOf(Product p) {
        return variants.findByProductId(p.getId()).stream().filter(v -> v.getBusinessId().equals(p.getBusinessId())).sorted(Comparator.comparing(ProductVariant::getCreatedAt))
            .map(v -> new VariantPrice(v.getId(), v.getLabel(), v.getPrice(), v.getCompareAtPrice())).toList();
    }

    List<ProductImage> galleryOf(UUID bid, UUID productId) {
        return images.findByBusinessIdAndProductIdAndGalleryTrueOrderByPositionAscCreatedAtAsc(bid, productId);
    }

    @Transactional
    public PresentationDto savePresentation(UUID productId, PresentationRequest r) {
        UUID bid = Tenant.businessId();
        Product p = mine(productId);
        p.setSpecs(parseSpecs(r.specs()));
        Map<UUID, BigDecimal> wanted = r.compareAt() == null ? Map.of() : r.compareAt();
        for (ProductVariant v : variants.findByProductId(productId)) {
            if (!v.getBusinessId().equals(bid) || !wanted.containsKey(v.getId())) continue;
            BigDecimal c = wanted.get(v.getId());
            if (c == null || c.signum() == 0) { v.setCompareAtPrice(null); continue; }
            if (c.compareTo(v.getPrice()) <= 0) throw ApiException.badRequest("The original price must be higher than the selling price (" + Money.scale(v.getPrice()).toPlainString() + ")");
            v.setCompareAtPrice(Money.scale(c));
            v.setUpdatedAt(Instant.now());
        }
        p.setUpdatedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "PRODUCT_STORE_DETAILS_SAVED", "product", productId, null);
        return presentationDto(p);
    }

    /** Normalises to "Label: value" lines, at most 20, so the product page never renders free-form clutter. */
    static String parseSpecs(String raw) {
        if (raw == null || raw.isBlank()) return null;
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            int i = t.indexOf(':');
            if (i <= 0 || i == t.length() - 1) throw ApiException.badRequest("Write each specification as Label: value (for example Material: Cotton)");
            out.add(t.substring(0, i).trim() + ": " + t.substring(i + 1).trim());
        }
        if (out.size() > 20) throw ApiException.badRequest("Use 20 specifications or fewer");
        return out.isEmpty() ? null : String.join("\n", out);
    }

    @Transactional
    public PresentationDto addGallery(UUID productId, byte[] bytes) {
        UUID bid = Tenant.businessId();
        Product p = mine(productId);
        if (bytes == null || bytes.length == 0) throw ApiException.badRequest("Choose an image");
        if (bytes.length > 600 * 1024) throw ApiException.badRequest("The image is too large. Use one under 600 KB.");
        String type = ProductImageController.sniff(bytes);
        if (type == null) throw ApiException.badRequest("Use a JPEG, PNG or WebP image");
        List<ProductImage> cur = galleryOf(bid, productId);
        if (cur.size() >= MAX_GALLERY) throw ApiException.badRequest("A product can have up to " + MAX_GALLERY + " extra photos");
        ProductImage img = new ProductImage();
        img.setBusinessId(bid);
        img.setProductId(productId);
        img.setContentType(type);
        img.setSizeBytes(bytes.length);
        img.setData(bytes);
        img.setGallery(true);
        img.setPosition(cur.size());
        images.save(img);
        return presentationDto(p);
    }

    @Transactional
    public PresentationDto removeGallery(UUID productId, UUID imageId) {
        UUID bid = Tenant.businessId();
        Product p = mine(productId);
        ProductImage img = images.findByIdAndBusinessId(imageId, bid).filter(i -> productId.equals(i.getProductId()) && i.isGallery()).orElseThrow(() -> ApiException.notFound("Photo not found"));
        images.delete(img);
        return presentationDto(p);
    }

    // ---------- reviews ----------
    private static String firstName(String full) {
        String n = full == null ? "" : full.trim();
        int sp = n.indexOf(' ');
        String f = sp > 0 ? n.substring(0, sp) : n;
        return f.isEmpty() ? "Customer" : f.length() > 30 ? f.substring(0, 30) : f;
    }

    /** Only a customer whose order for this product was completed can review it, once per order. It is held for the merchant to approve. */
    @Transactional
    public Map<String, String> submitReview(String slug, ReviewRequest r) {
        var live = orderService.live(slug);
        UUID bid = live.business().getId();
        StoreOrder o = orders.findByPublicToken(r.token()).filter(x -> x.getBusinessId().equals(bid)).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!"COMPLETED".equals(o.getStatus())) throw ApiException.conflict("You can review an item once your order has been completed");
        Set<UUID> variantIds = orderItems.findByOrderId(o.getId()).stream().map(StoreOrderItem::getVariantId).collect(Collectors.toSet());
        boolean bought = variants.findAllById(variantIds).stream().anyMatch(v -> v.getProduct().getId().equals(r.productId()) && v.getBusinessId().equals(bid));
        if (!bought) throw ApiException.badRequest("That item is not part of this order");
        if (reviews.existsByOrderIdAndProductId(o.getId(), r.productId())) throw ApiException.conflict("You have already reviewed this item");
        StoreReview v = new StoreReview();
        v.setBusinessId(bid);
        v.setProductId(r.productId());
        v.setOrderId(o.getId());
        v.setAuthorName(firstName(o.getCustomerName()));
        v.setRating(r.rating());
        v.setBody(r.body().trim());
        reviews.save(v);
        notifications.publish(bid, Permission.STORE_ORDERS_MANAGE, "STORE_REVIEW", "New review to approve", v.getAuthorName() + " gave " + v.getRating() + " stars", "/more/store-inbox", "store-review-" + v.getId());
        return Map.of("status", "PENDING");
    }

    @Transactional(readOnly = true)
    public List<ReviewDto> publicReviews(String slug, UUID productId) {
        UUID bid = orderService.live(slug).business().getId();
        return reviews.findTop200ByBusinessIdAndProductIdAndStatusOrderByCreatedAtDesc(bid, productId, "APPROVED").stream()
            .map(x -> new ReviewDto(x.getId(), x.getAuthorName(), x.getRating(), x.getBody(), x.getCreatedAt(), null)).toList();
    }

    /** Real approved reviews of 4 stars or more, for the home page. Empty when the store has none. */
    @Transactional(readOnly = true)
    public List<ReviewDto> testimonials(String slug) {
        UUID bid = orderService.live(slug).business().getId();
        List<StoreReview> rows = reviews.findTop12ByBusinessIdAndStatusAndRatingGreaterThanEqualOrderByCreatedAtDesc(bid, "APPROVED", 4);
        Map<UUID, String> names = products.findAllById(rows.stream().map(StoreReview::getProductId).collect(Collectors.toSet())).stream()
            .filter(p -> p.getBusinessId().equals(bid)).collect(Collectors.toMap(Product::getId, Product::getName));
        return rows.stream().limit(6).map(x -> new ReviewDto(x.getId(), x.getAuthorName(), x.getRating(), x.getBody(), x.getCreatedAt(), names.get(x.getProductId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<ReviewAdminDto> adminReviews() {
        UUID bid = Tenant.businessId();
        List<StoreReview> rows = reviews.findTop200ByBusinessIdOrderByCreatedAtDesc(bid);
        Map<UUID, String> names = products.findAllById(rows.stream().map(StoreReview::getProductId).collect(Collectors.toSet())).stream()
            .filter(p -> p.getBusinessId().equals(bid)).collect(Collectors.toMap(Product::getId, Product::getName));
        return rows.stream().map(x -> new ReviewAdminDto(x.getId(), names.getOrDefault(x.getProductId(), "Product"), x.getAuthorName(), x.getRating(), x.getBody(), x.getStatus(), x.getCreatedAt())).toList();
    }

    @Transactional
    public ReviewAdminDto setReviewStatus(UUID id, String to) {
        UUID bid = Tenant.businessId();
        String t = to == null ? "" : to.toUpperCase();
        if (!t.equals("APPROVED") && !t.equals("HIDDEN")) throw ApiException.badRequest("Use approve or hide");
        StoreReview x = reviews.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Review not found"));
        x.setStatus(t);
        audit.record(bid, Tenant.userId(), "STORE_REVIEW_" + t, "store_review", id, null);
        String name = products.findById(x.getProductId()).filter(p -> p.getBusinessId().equals(bid)).map(Product::getName).orElse("Product");
        return new ReviewAdminDto(x.getId(), name, x.getAuthorName(), x.getRating(), x.getBody(), x.getStatus(), x.getCreatedAt());
    }

    // ---------- newsletter ----------
    @Transactional
    public Map<String, String> subscribe(String slug, SubscribeRequest r) {
        UUID bid = orderService.live(slug).business().getId();
        String email = r.email().trim().toLowerCase();
        // The same answer whether or not the address was already on the list, so the form cannot be used to probe who subscribed.
        if (!subscribers.existsByBusinessIdAndEmailIgnoreCase(bid, email)) {
            StoreSubscriber s = new StoreSubscriber();
            s.setBusinessId(bid);
            s.setEmail(email);
            subscribers.save(s);
        }
        return Map.of("status", "SUBSCRIBED");
    }

    @Transactional(readOnly = true)
    public List<SubscriberDto> adminSubscribers() {
        return subscribers.findTop1000ByBusinessIdOrderByCreatedAtDesc(Tenant.businessId()).stream().map(s -> new SubscriberDto(s.getEmail(), s.getCreatedAt())).toList();
    }

    // ---------- catering enquiries ----------
    @Transactional
    public Map<String, String> enquire(String slug, EnquiryRequest r) {
        UUID bid = orderService.live(slug).business().getId();
        if (r.eventDate() != null && r.eventDate().isBefore(LocalDate.now().minusDays(1))) throw ApiException.badRequest("Choose a date that has not passed");
        StoreEnquiry e = new StoreEnquiry();
        e.setBusinessId(bid);
        e.setName(r.name().trim());
        e.setPhone(r.phone().trim());
        e.setEmail(blank(r.email()));
        e.setEventDate(r.eventDate());
        e.setGuests(r.guests());
        e.setMessage(r.message().trim());
        enquiries.save(e);
        notifications.publish(bid, Permission.STORE_ORDERS_MANAGE, "STORE_ENQUIRY", "New enquiry from " + e.getName(), e.getMessage().length() > 90 ? e.getMessage().substring(0, 90) + "…" : e.getMessage(), "/more/store-inbox", "store-enquiry-" + e.getId());
        return Map.of("status", "RECEIVED");
    }

    @Transactional(readOnly = true)
    public List<EnquiryDto> adminEnquiries() {
        return enquiries.findTop200ByBusinessIdOrderByCreatedAtDesc(Tenant.businessId()).stream().map(StoreExtrasService::dto).toList();
    }

    @Transactional
    public EnquiryDto setEnquiryStatus(UUID id, String to) {
        UUID bid = Tenant.businessId();
        String t = to == null ? "" : to.toUpperCase();
        if (!List.of("NEW", "CONTACTED", "CLOSED").contains(t)) throw ApiException.badRequest("Unknown status");
        StoreEnquiry e = enquiries.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Enquiry not found"));
        e.setStatus(t);
        return dto(e);
    }

    private static EnquiryDto dto(StoreEnquiry e) { return new EnquiryDto(e.getId(), e.getName(), e.getPhone(), e.getEmail(), e.getEventDate(), e.getGuests(), e.getMessage(), e.getStatus(), e.getCreatedAt()); }

    // ---------- order lookup (no accounts: the phone number used on the order plus its number) ----------
    @Transactional(readOnly = true)
    public StoreOrderService.OrderDto lookup(String slug, LookupRequest r) {
        UUID bid = orderService.live(slug).business().getId();
        StoreOrder o = orders.findByBusinessIdAndNumber(bid, r.number()).filter(x -> sameLine(x.getPhone(), r.phone())).orElseThrow(() -> ApiException.notFound("We could not find an order with that number and phone"));
        return orderService.status(o.getPublicToken());
    }

    static boolean sameLine(String a, String b) {
        String x = a == null ? "" : a.replaceAll("\\D", "");
        String y = b == null ? "" : b.replaceAll("\\D", "");
        if (x.length() < 7 || y.length() < 7) return false;
        return x.endsWith(y.length() > 10 ? y.substring(y.length() - 10) : y) || y.endsWith(x.length() > 10 ? x.substring(x.length() - 10) : x);
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
