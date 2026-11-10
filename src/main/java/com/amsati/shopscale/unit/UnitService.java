package com.amsati.shopscale.unit;

import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.Tenant;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Units of measure, decimal-quantity rules and per-product unit conversion (e.g. 1 carton = 24 pieces). */
@Service
@RequiredArgsConstructor
public class UnitService {
    public static final List<String> COMMON_UNITS = List.of("piece", "kg", "g", "litre", "ml", "pack", "carton", "dozen",
        "bag", "bottle", "crate", "tray", "portion", "plate", "bundle", "metre");
    public static final int MAX_SCALE = 3;

    private final UnitConversionRepository conversions;
    private final ProductVariantRepository variants;

    public record ConversionRequest(@NotNull UUID variantId, @NotBlank @Size(max = 20) String unit,
                                    @NotNull @DecimalMin(value = "0.000001", message = "Enter how many base units it holds") BigDecimal factor) {}
    public record ConversionDto(UUID id, UUID variantId, String unit, BigDecimal factor) {}

    public String normalizeUnit(String u) {
        String n = u == null ? "" : u.trim().toLowerCase();
        if (n.isEmpty()) return "piece";
        if (n.length() > 20) throw ApiException.badRequest("Unit name is too long");
        return n;
    }

    /** Quantity must be positive, at most 3 decimal places, and whole unless the product allows decimals. */
    public void requireValidQuantity(ProductVariant v, BigDecimal q) {
        if (q == null || q.signum() <= 0) throw ApiException.badRequest("Enter a quantity greater than zero");
        if (q.stripTrailingZeros().scale() > MAX_SCALE) throw ApiException.badRequest("Use at most 3 decimal places");
        if (!v.isAllowDecimal() && q.stripTrailingZeros().scale() > 0) {
            throw ApiException.badRequest("\"" + display(v) + "\" is counted in whole " + v.getUnit() + "s. Enter a whole number.");
        }
    }

    /** Convert a quantity entered in {@code unit} to the variant's base unit. Null/base unit returns the quantity unchanged. */
    public BigDecimal toBase(ProductVariant v, String unit, BigDecimal qty) {
        if (unit == null || unit.isBlank() || unit.trim().equalsIgnoreCase(v.getUnit())) return qty;
        UnitConversion c = conversions.findByBusinessIdAndVariantIdAndUnit(v.getBusinessId(), v.getId(), unit.trim().toLowerCase())
            .orElseThrow(() -> ApiException.badRequest("No conversion from " + unit + " to " + v.getUnit() + " is set for \"" + display(v) + "\""));
        return qty.multiply(c.getFactor()).setScale(MAX_SCALE, RoundingMode.HALF_UP);
    }

    private String display(ProductVariant v) {
        return v.getLabel() == null ? v.getProduct().getName() : v.getProduct().getName() + " (" + v.getLabel() + ")";
    }

    @Transactional(readOnly = true)
    public List<ConversionDto> list(UUID variantId) {
        return conversions.findByBusinessIdAndVariantIdOrderByUnitAsc(Tenant.businessId(), variantId).stream().map(UnitService::dto).toList();
    }

    @Transactional
    public ConversionDto save(ConversionRequest r) {
        UUID bid = Tenant.businessId();
        ProductVariant v = variants.findById(r.variantId()).filter(x -> x.getBusinessId().equals(bid))
            .orElseThrow(() -> ApiException.notFound("Product variant not found"));
        String unit = normalizeUnit(r.unit());
        if (unit.equals(v.getUnit())) throw ApiException.badRequest("That is already the base unit of this product");
        UnitConversion c = conversions.findByBusinessIdAndVariantIdAndUnit(bid, v.getId(), unit).orElseGet(UnitConversion::new);
        c.setBusinessId(bid); c.setVariantId(v.getId()); c.setUnit(unit); c.setFactor(r.factor());
        return dto(conversions.save(c));
    }

    @Transactional
    public void delete(UUID id) {
        UnitConversion c = conversions.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Conversion not found"));
        conversions.delete(c);
    }

    private static ConversionDto dto(UnitConversion c) { return new ConversionDto(c.getId(), c.getVariantId(), c.getUnit(), c.getFactor()); }
}
