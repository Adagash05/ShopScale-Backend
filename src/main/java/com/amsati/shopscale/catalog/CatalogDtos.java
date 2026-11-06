package com.amsati.shopscale.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CatalogDtos {
    private CatalogDtos() {}

    public record VariantRequest(
        UUID id,
        @Size(max = 120) String label,
        @Size(max = 60) String sku,
        @Size(max = 60) String barcode,
        @NotNull @DecimalMin(value = "0", message = "Price cannot be negative") BigDecimal price,
        @DecimalMin(value = "0", message = "Cost cannot be negative") BigDecimal cost,
        @Min(0) Integer quantity,
        @Min(0) Integer lowStockThreshold) {}

    public record ProductRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        UUID categoryId,
        @Size(max = 500) String imageUrl,
        @NotEmpty @Valid List<VariantRequest> variants) {}

    public record CategoryRequest(@NotBlank @Size(max = 80) String name) {}

    public record VariantDto(UUID id, String label, String sku, String barcode, BigDecimal price, BigDecimal cost,
                             int quantity, int lowStockThreshold, boolean active) {}

    public record ProductDto(UUID id, String name, String description, String imageUrl, UUID categoryId,
                             String categoryName, ProductStatus status, List<VariantDto> variants, int totalQuantity,
                             Instant createdAt) {}

    public record CategoryDto(UUID id, String name) {}

    public record PosItemDto(UUID variantId, UUID productId, String productName, String label, String sku,
                             BigDecimal price, int quantity) {}
}
