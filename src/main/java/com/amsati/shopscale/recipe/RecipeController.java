package com.amsati.shopscale.recipe;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recipes")
@RequiredArgsConstructor
public class RecipeController {
    private final RecipeService service;

    @GetMapping("/{variantId}")
    @PreAuthorize("@access.has('RECIPES_MANAGE') or @access.has('PRODUCTS_VIEW')")
    public org.springframework.http.ResponseEntity<RecipeService.RecipeDto> get(@PathVariable UUID variantId) {
        RecipeService.RecipeDto d = service.get(variantId);
        return d == null ? org.springframework.http.ResponseEntity.noContent().build() : org.springframework.http.ResponseEntity.ok(d);
    }

    @PutMapping("/{variantId}")
    @PreAuthorize("@access.has('RECIPES_MANAGE')")
    public RecipeService.RecipeDto save(@PathVariable UUID variantId, @Valid @RequestBody RecipeService.RecipeRequest r) { return service.save(variantId, r); }

    @DeleteMapping("/{variantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has('RECIPES_MANAGE')")
    public void delete(@PathVariable UUID variantId) { service.delete(variantId); }
}
