package com.amsati.shopscale.catering;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.industry.IndustryGuard;
import com.amsati.shopscale.industry.Workflow;
import com.amsati.shopscale.recipe.Recipe;
import com.amsati.shopscale.recipe.RecipeService;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catering packages (what is served, per guest) and add-on services (staff, equipment, delivery). */
@Service
@RequiredArgsConstructor
public class CateringCatalogService {
    private final JdbcTemplate jdbc;
    private final ProductVariantRepository variants;
    private final RecipeService recipes;
    private final IndustryGuard industry;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    public record LineRequest(@NotNull UUID variantId, @NotNull @DecimalMin(value = "0.0001", message = "Quantity per guest must be above zero") BigDecimal qtyPerGuest) {}
    public record PackageRequest(@NotBlank @Size(max = 120) String name, @Size(max = 1000) String description, @Size(max = 2000) String inclusions,
                                 @NotNull @DecimalMin("0") BigDecimal basePrice, @NotNull @DecimalMin("0") BigDecimal pricePerGuest,
                                 @Min(1) int minGuests, boolean active, @Valid List<LineRequest> lines) {}
    public record LineDto(UUID variantId, String name, String unit, BigDecimal qtyPerGuest, BigDecimal costPerGuest) {}
    public record PackageDto(UUID id, String name, String description, String inclusions, BigDecimal basePrice, BigDecimal pricePerGuest, int minGuests,
                             boolean active, BigDecimal costPerGuest, List<LineDto> lines) {}
    public record ServiceRequest(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description, @NotNull @DecimalMin("0") BigDecimal price,
                                 @NotNull String basis, boolean active) {}
    public record ServiceDto(UUID id, String name, String description, BigDecimal price, String basis, boolean active) {}

    private UUID gate() {
        UUID bid = Tenant.businessId();
        industry.require(bid, Workflow.CATERING);
        subscriptions.requireFeature(bid, "CATERING", "Catering");
        return bid;
    }

    // ---------------------------------------------------------------- packages
    @Transactional(readOnly = true)
    public List<PackageDto> packages(boolean includeInactive) {
        UUID bid = gate();
        List<UUID> ids = jdbc.queryForList("select id from catering_packages where business_id = ? " + (includeInactive ? "" : "and active = true ") + "order by lower(name)", UUID.class, bid);
        List<PackageDto> out = new ArrayList<>();
        for (UUID id : ids) out.add(load(bid, id));
        return out;
    }

    @Transactional(readOnly = true)
    public PackageDto pkg(UUID id) { return load(gate(), id); }

    private PackageDto load(UUID bid, UUID id) {
        List<Object[]> rows = jdbc.query("select name, description, inclusions, base_price, price_per_guest, min_guests, active from catering_packages where id = ? and business_id = ?",
            (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getInt(6), rs.getBoolean(7)}, id, bid);
        if (rows.isEmpty()) throw ApiException.notFound("Package not found");
        Object[] r = rows.get(0);
        List<UUID[]> raw = new ArrayList<>();
        List<BigDecimal> qtys = new ArrayList<>();
        jdbc.query("select variant_id, qty_per_guest from catering_package_lines where package_id = ?", (rs) -> { raw.add(new UUID[]{rs.getObject(1, UUID.class)}); qtys.add(rs.getBigDecimal(2)); }, id);
        Map<UUID, ProductVariant> vs = variants.findAllById(raw.stream().map(a -> a[0]).toList()).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        Map<UUID, Recipe> made = recipes.madeToOrder(bid, vs.keySet());
        List<LineDto> lines = new ArrayList<>();
        BigDecimal perGuest = BigDecimal.ZERO;
        for (int i = 0; i < raw.size(); i++) {
            ProductVariant v = vs.get(raw.get(i)[0]);
            if (v == null) continue;
            BigDecimal unit = made.containsKey(v.getId()) ? recipes.unitCost(made.get(v.getId())) : v.getCost();
            BigDecimal cost = Money.scale(unit.multiply(qtys.get(i)));
            perGuest = perGuest.add(cost);
            lines.add(new LineDto(v.getId(), v.getProduct().getName() + (v.getLabel() == null || v.getLabel().isBlank() ? "" : " (" + v.getLabel() + ")"), v.getUnit(), qtys.get(i), cost));
        }
        return new PackageDto(id, (String) r[0], (String) r[1], (String) r[2], (BigDecimal) r[3], (BigDecimal) r[4], (Integer) r[5], (Boolean) r[6], Money.scale(perGuest), lines);
    }

    @Transactional
    public PackageDto savePackage(UUID id, PackageRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        List<LineRequest> lines = r.lines() == null ? List.of() : r.lines();
        Set<UUID> vids = lines.stream().map(LineRequest::variantId).collect(Collectors.toSet());
        if (vids.size() != lines.size()) throw ApiException.badRequest("Each ingredient or dish can only be listed once");
        List<ProductVariant> found = vids.isEmpty() ? List.of() : variants.findAllById(vids).stream().filter(v -> v.getBusinessId().equals(bid)).toList();
        if (found.size() != vids.size()) throw ApiException.badRequest("One of the listed items was not found");
        if (r.basePrice().signum() == 0 && r.pricePerGuest().signum() == 0) throw ApiException.badRequest("Set a price per guest or a fixed price for the package");
        try {
            if (id == null) {
                id = UUID.randomUUID();
                jdbc.update("insert into catering_packages (id, business_id, name, description, inclusions, base_price, price_per_guest, min_guests, active) values (?,?,?,?,?,?,?,?,?)",
                    id, bid, r.name().trim(), blank(r.description()), blank(r.inclusions()), Money.scale(r.basePrice()), Money.scale(r.pricePerGuest()), r.minGuests(), r.active());
            } else {
                int n = jdbc.update("update catering_packages set name=?, description=?, inclusions=?, base_price=?, price_per_guest=?, min_guests=?, active=?, updated_at=now() where id=? and business_id=?",
                    r.name().trim(), blank(r.description()), blank(r.inclusions()), Money.scale(r.basePrice()), Money.scale(r.pricePerGuest()), r.minGuests(), r.active(), id, bid);
                if (n == 0) throw ApiException.notFound("Package not found");
                jdbc.update("delete from catering_package_lines where package_id = ?", id);
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("You already have a package called " + r.name().trim());
        }
        for (LineRequest l : lines) jdbc.update("insert into catering_package_lines (id, package_id, variant_id, qty_per_guest) values (?,?,?,?)", UUID.randomUUID(), id, l.variantId(), l.qtyPerGuest());
        audit.record(bid, Tenant.userId(), "CATERING_PACKAGE_SAVED", "catering_package", id, r.name().trim());
        return load(bid, id);
    }

    // ---------------------------------------------------------------- services
    @Transactional(readOnly = true)
    public List<ServiceDto> services(boolean includeInactive) {
        UUID bid = gate();
        return jdbc.query("select id, name, description, price, basis, active from catering_services where business_id = ? " + (includeInactive ? "" : "and active = true ") + "order by lower(name)",
            (rs, i) -> new ServiceDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5), rs.getBoolean(6)), bid);
    }

    @Transactional
    public ServiceDto saveService(UUID id, ServiceRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        if (!Set.of("FIXED", "PER_GUEST").contains(r.basis())) throw ApiException.badRequest("Choose whether the price is fixed or per guest");
        try {
            if (id == null) {
                id = UUID.randomUUID();
                jdbc.update("insert into catering_services (id, business_id, name, description, price, basis, active) values (?,?,?,?,?,?,?)", id, bid, r.name().trim(), blank(r.description()), Money.scale(r.price()), r.basis(), r.active());
            } else if (jdbc.update("update catering_services set name=?, description=?, price=?, basis=?, active=? where id=? and business_id=?", r.name().trim(), blank(r.description()), Money.scale(r.price()), r.basis(), r.active(), id, bid) == 0) {
                throw ApiException.notFound("Service not found");
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("You already have a service called " + r.name().trim());
        }
        audit.record(bid, Tenant.userId(), "CATERING_SERVICE_SAVED", "catering_service", id, r.name().trim());
        UUID sid = id;
        return jdbc.query("select id, name, description, price, basis, active from catering_services where id = ?",
            (rs, i) -> new ServiceDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5), rs.getBoolean(6)), sid).get(0);
    }

    /** Ingredient quantities for a package and guest count. Menu items with a recipe expand into their ingredients; everything else is taken as listed. */
    public Map<UUID, BigDecimal> requirements(UUID bid, UUID packageId, int guests) {
        Map<UUID, BigDecimal> need = new LinkedHashMap<>();
        if (packageId == null) return need;
        List<UUID> vids = new ArrayList<>();
        List<BigDecimal> qtys = new ArrayList<>();
        jdbc.query("select variant_id, qty_per_guest from catering_package_lines where package_id = ?", (rs) -> { vids.add(rs.getObject(1, UUID.class)); qtys.add(rs.getBigDecimal(2)); }, packageId);
        Map<UUID, Recipe> made = recipes.madeToOrder(bid, new HashSet<>(vids));
        for (int i = 0; i < vids.size(); i++) {
            BigDecimal total = qtys.get(i).multiply(BigDecimal.valueOf(guests)).setScale(3, java.math.RoundingMode.HALF_UP);
            Recipe rec = made.get(vids.get(i));
            if (rec != null) recipes.requirements(rec, total).forEach((k, q) -> need.merge(k, q, BigDecimal::add));
            else need.merge(vids.get(i), total, BigDecimal::add);
        }
        return need;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
