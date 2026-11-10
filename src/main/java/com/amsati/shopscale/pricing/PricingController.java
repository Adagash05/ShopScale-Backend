package com.amsati.shopscale.pricing;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pricing")
@RequiredArgsConstructor
public class PricingController {
    private final PricingService service;

    @GetMapping("/tiers")
    @PreAuthorize("@access.has('CUSTOMERS_VIEW') or @access.has('PRODUCTS_VIEW')")
    public List<PricingService.TierDto> tiers() { return service.listTiers(); }

    @PostMapping("/tiers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('PRICING_MANAGE')")
    public PricingService.TierDto createTier(@Valid @RequestBody PricingService.TierRequest r) { return service.createTier(r); }

    @PostMapping("/tiers/{id}/activate")
    @PreAuthorize("@access.has('PRICING_MANAGE')")
    public PricingService.TierDto activate(@PathVariable UUID id) { return service.setTierActive(id, true); }

    @PostMapping("/tiers/{id}/deactivate")
    @PreAuthorize("@access.has('PRICING_MANAGE')")
    public PricingService.TierDto deactivate(@PathVariable UUID id) { return service.setTierActive(id, false); }

    @GetMapping("/variants/{variantId}")
    @PreAuthorize("@access.has('PRODUCTS_VIEW')")
    public List<PricingService.RuleDto> rules(@PathVariable UUID variantId) { return service.rulesFor(variantId); }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('PRICING_MANAGE')")
    public PricingService.RuleDto add(@Valid @RequestBody PricingService.RuleRequest r) { return service.addRule(r); }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has('PRICING_MANAGE')")
    public void delete(@PathVariable UUID id) { service.deleteRule(id); }
}
