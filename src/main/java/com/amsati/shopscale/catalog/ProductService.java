package com.amsati.shopscale.catalog;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.CatalogDtos.*;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.inventory.MovementType;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;
    private final BusinessRepository businesses;
    private final StockService stock;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    private static String like(String q) {
        return "%" + (q == null ? "" : q.trim().toLowerCase().replace("%", "").replace("_", "")) + "%";
    }

    // ---------- categories ----------
    @Transactional(readOnly = true)
    public List<CategoryDto> listCategories() {
        return categories.findByBusinessIdOrderByNameAsc(Tenant.businessId()).stream()
            .map(c -> new CategoryDto(c.getId(), c.getName())).toList();
    }

    @Transactional
    public CategoryDto createCategory(CategoryRequest r) {
        UUID bid = Tenant.businessId();
        String name = r.name().trim();
        if (categories.existsByBusinessIdAndNameIgnoreCase(bid, name)) throw ApiException.conflict("You already have a category with that name");
        Category c = new Category();
        c.setBusinessId(bid);
        c.setName(name);
        categories.save(c);
        return new CategoryDto(c.getId(), c.getName());
    }

    @Transactional
    public void deleteCategory(UUID id) {
        Category c = categories.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Category not found"));
        categories.delete(c); // products fall back to "no category" via ON DELETE SET NULL
    }

    // ---------- reads ----------
    @Transactional(readOnly = true)
    public Page<ProductDto> list(String q, UUID categoryId, boolean archived, int page, int size) {
        UUID bid = Tenant.businessId();
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        ProductStatus status = archived ? ProductStatus.ARCHIVED : ProductStatus.ACTIVE;
        Page<Product> found = categoryId == null
            ? products.search(bid, status, like(q), pageable)
            : products.searchInCategory(bid, status, categoryId, like(q), pageable);
        return toDtos(bid, found);
    }

    @Transactional(readOnly = true)
    public ProductDto get(UUID id) {
        UUID bid = Tenant.businessId();
        Product p = products.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Product not found"));
        return toDtos(bid, new org.springframework.data.domain.PageImpl<>(List.of(p))).getContent().get(0);
    }

    private Page<ProductDto> toDtos(UUID bid, Page<Product> page) {
        List<UUID> ids = page.getContent().stream().map(Product::getId).toList();
        Map<UUID, List<ProductVariant>> byProduct = ids.isEmpty() ? Map.of()
            : variants.findByProductIdIn(ids).stream().filter(ProductVariant::isActive)
                .sorted(Comparator.comparing(v -> v.getLabel() == null ? "" : v.getLabel()))
                .collect(Collectors.groupingBy(v -> v.getProduct().getId()));
        Map<UUID, String> categoryNames = categories.findByBusinessIdOrderByNameAsc(bid).stream()
            .collect(Collectors.toMap(Category::getId, Category::getName));
        return page.map(p -> {
            List<VariantDto> vs = byProduct.getOrDefault(p.getId(), List.of()).stream().map(ProductService::toDto).toList();
            int total = vs.stream().mapToInt(VariantDto::quantity).sum();
            return new ProductDto(p.getId(), p.getName(), p.getDescription(), p.getImageUrl(), p.getCategoryId(),
                p.getCategoryId() == null ? null : categoryNames.get(p.getCategoryId()), p.getStatus(), vs, total, p.getCreatedAt());
        });
    }

    private static VariantDto toDto(ProductVariant v) {
        return new VariantDto(v.getId(), v.getLabel(), v.getSku(), v.getBarcode(), v.getPrice(), v.getCost(),
            v.getQuantity(), v.getLowStockThreshold(), v.isActive());
    }

    /** Flat list of sellable variants for the POS search box. */
    @Transactional(readOnly = true)
    public List<PosItemDto> sellable(String q) {
        return variants.sellable(Tenant.businessId(), ProductStatus.ACTIVE, like(q), PageRequest.of(0, 40)).stream()
            .map(v -> new PosItemDto(v.getId(), v.getProduct().getId(), v.getProduct().getName(), v.getLabel(),
                v.getSku(), v.getPrice(), v.getQuantity())).toList();
    }

    // ---------- writes ----------
    @Transactional
    public ProductDto create(ProductRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireItemCapacity(bid, products.countByBusinessIdAndStatus(bid, ProductStatus.ACTIVE) + 1);
        validateCategory(bid, r.categoryId());
        validateVariantInputs(bid, null, r.variants());
        Business b = businesses.findById(bid).orElseThrow();

        Product p = new Product();
        p.setBusinessId(bid);
        p.setName(r.name().trim());
        p.setDescription(r.description());
        p.setImageUrl(r.imageUrl());
        p.setCategoryId(r.categoryId());
        products.save(p);

        for (VariantRequest vr : r.variants()) createVariant(p, vr, b.isAllowNegativeStock());
        audit.record(bid, Tenant.userId(), "PRODUCT_CREATED", "product", p.getId(), p.getName());
        return get(p.getId());
    }

    @Transactional
    public ProductDto update(UUID id, ProductRequest r) {
        UUID bid = Tenant.businessId();
        Product p = products.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Product not found"));
        validateCategory(bid, r.categoryId());
        validateVariantInputs(bid, id, r.variants());
        Business b = businesses.findById(bid).orElseThrow();

        p.setName(r.name().trim());
        p.setDescription(r.description());
        p.setImageUrl(r.imageUrl());
        p.setCategoryId(r.categoryId());
        p.setUpdatedAt(Instant.now());

        Map<UUID, ProductVariant> existing = variants.findByProductId(id).stream()
            .filter(ProductVariant::isActive).collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        Set<UUID> kept = new HashSet<>();
        for (VariantRequest vr : r.variants()) {
            if (vr.id() == null) {
                createVariant(p, vr, b.isAllowNegativeStock());
            } else {
                ProductVariant v = existing.get(vr.id());
                if (v == null) throw ApiException.badRequest("One of the variants does not belong to this product");
                kept.add(v.getId());
                v.setLabel(blankToNull(vr.label()));
                v.setSku(blankToNull(vr.sku()));
                v.setBarcode(blankToNull(vr.barcode()));
                v.setPrice(Money.scale(vr.price()));
                v.setCost(Money.scale(vr.cost()));
                if (vr.lowStockThreshold() != null) v.setLowStockThreshold(vr.lowStockThreshold());
                v.setUpdatedAt(Instant.now());
            }
        }
        for (ProductVariant v : existing.values()) {
            if (kept.contains(v.getId())) continue;
            if (v.getQuantity() > 0) {
                throw ApiException.badRequest("\"" + (v.getLabel() == null ? p.getName() : v.getLabel())
                    + "\" still has stock. Adjust its stock to zero before removing it.");
            }
            v.setActive(false);
            v.setUpdatedAt(Instant.now());
        }
        audit.record(bid, Tenant.userId(), "PRODUCT_UPDATED", "product", id, p.getName());
        return get(id);
    }

    @Transactional
    public ProductDto archive(UUID id) {
        UUID bid = Tenant.businessId();
        Product p = products.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Product not found"));
        p.setStatus(ProductStatus.ARCHIVED);
        p.setUpdatedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "PRODUCT_ARCHIVED", "product", id, p.getName());
        return get(id);
    }

    @Transactional
    public ProductDto restore(UUID id) {
        UUID bid = Tenant.businessId();
        Product p = products.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Product not found"));
        if (p.getStatus() == ProductStatus.ACTIVE) return get(id);
        subscriptions.requireItemCapacity(bid, products.countByBusinessIdAndStatus(bid, ProductStatus.ACTIVE) + 1);
        p.setStatus(ProductStatus.ACTIVE);
        p.setUpdatedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "PRODUCT_RESTORED", "product", id, p.getName());
        return get(id);
    }

    // ---------- helpers ----------
    private void createVariant(Product p, VariantRequest vr, boolean allowNegative) {
        ProductVariant v = new ProductVariant();
        v.setBusinessId(p.getBusinessId());
        v.setProduct(p);
        v.setLabel(blankToNull(vr.label()));
        v.setSku(blankToNull(vr.sku()));
        v.setBarcode(blankToNull(vr.barcode()));
        v.setPrice(Money.scale(vr.price()));
        v.setCost(Money.scale(vr.cost()));
        v.setLowStockThreshold(vr.lowStockThreshold() == null ? 5 : vr.lowStockThreshold());
        v.setQuantity(0);
        variants.save(v);
        int opening = vr.quantity() == null ? 0 : vr.quantity();
        if (opening > 0) stock.apply(v, MovementType.OPENING, opening, "Opening stock", null, Tenant.userId(), allowNegative);
    }

    private void validateCategory(UUID bid, UUID categoryId) {
        if (categoryId != null && categories.findByIdAndBusinessId(categoryId, bid).isEmpty()) {
            throw ApiException.badRequest("Choose a category from your list");
        }
    }

    private void validateVariantInputs(UUID bid, UUID productId, List<VariantRequest> reqs) {
        if (reqs.size() > 1) {
            Set<String> labels = new HashSet<>();
            for (VariantRequest vr : reqs) {
                String l = vr.label() == null ? "" : vr.label().trim().toLowerCase();
                if (l.isEmpty()) throw ApiException.badRequest("Give each variant a name, such as Size M or Red");
                if (!labels.add(l)) throw ApiException.badRequest("Two variants have the same name: " + vr.label());
            }
        }
        Set<String> skus = new HashSet<>();
        for (VariantRequest vr : reqs) {
            String sku = blankToNull(vr.sku());
            if (sku == null) continue;
            if (!skus.add(sku.toLowerCase())) throw ApiException.badRequest("SKU " + sku + " is used twice in this product");
            variants.findByBusinessIdAndSkuIgnoreCase(bid, sku).ifPresent(other -> {
                boolean sameProduct = productId != null && other.getProduct().getId().equals(productId)
                    && vr.id() != null && vr.id().equals(other.getId());
                if (!sameProduct) throw ApiException.conflict("SKU " + sku + " is already used by another product");
            });
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
