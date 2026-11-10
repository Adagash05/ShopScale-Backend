package com.amsati.shopscale.catalog;

import com.amsati.shopscale.catalog.CatalogDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ProductController {
    private final ProductService service;

    @GetMapping("/api/products")
    @PreAuthorize("@access.has('PRODUCTS_VIEW')")
    public Page<ProductDto> list(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) UUID categoryId,
                                 @RequestParam(defaultValue = "false") boolean archived,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return service.list(q, categoryId, archived, page, size);
    }

    @GetMapping("/api/products/sellable")
    @PreAuthorize("@access.has('SALES_CREATE') or @access.has('PRODUCTS_VIEW')")
    public List<PosItemDto> sellable(@RequestParam(required = false) String q, @RequestParam(required = false) UUID branchId,
                                       @RequestParam(required = false) UUID customerId,
                                       @RequestParam(defaultValue = "40") int size) { return service.sellable(q, branchId, customerId, size); }

    @GetMapping("/api/products/{id}")
    @PreAuthorize("@access.has('PRODUCTS_VIEW')")
    public ProductDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping("/api/products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('PRODUCTS_CREATE')")
    public ProductDto create(@Valid @RequestBody ProductRequest r) { return service.create(r); }

    @PutMapping("/api/products/{id}")
    @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    public ProductDto update(@PathVariable UUID id, @Valid @RequestBody ProductRequest r) { return service.update(id, r); }

    @PostMapping("/api/products/{id}/archive")
    @PreAuthorize("@access.has('PRODUCTS_ARCHIVE')")
    public ProductDto archive(@PathVariable UUID id) { return service.archive(id); }

    @PostMapping("/api/products/{id}/restore")
    @PreAuthorize("@access.has('PRODUCTS_ARCHIVE')")
    public ProductDto restore(@PathVariable UUID id) { return service.restore(id); }

    @GetMapping("/api/categories")
    @PreAuthorize("@access.has('PRODUCTS_VIEW') or @access.has('CATEGORIES_MANAGE')")
    public List<CategoryDto> categories() { return service.listCategories(); }

    @PostMapping("/api/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('CATEGORIES_MANAGE')")
    public CategoryDto createCategory(@Valid @RequestBody CategoryRequest r) { return service.createCategory(r); }

    @DeleteMapping("/api/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has('CATEGORIES_MANAGE')")
    public void deleteCategory(@PathVariable UUID id) { service.deleteCategory(id); }
}
