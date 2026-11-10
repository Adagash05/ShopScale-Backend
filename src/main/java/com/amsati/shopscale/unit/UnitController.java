package com.amsati.shopscale.unit;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/units")
@RequiredArgsConstructor
public class UnitController {
    private final UnitService service;

    @GetMapping
    public List<String> common() { return UnitService.COMMON_UNITS; }

    @GetMapping("/conversions")
    @PreAuthorize("@access.has('PRODUCTS_VIEW')")
    public List<UnitService.ConversionDto> list(@RequestParam UUID variantId) { return service.list(variantId); }

    @PostMapping("/conversions")
    @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    public UnitService.ConversionDto save(@Valid @RequestBody UnitService.ConversionRequest r) { return service.save(r); }

    @DeleteMapping("/conversions/{id}")
    @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    public void delete(@PathVariable UUID id) { service.delete(id); }
}
