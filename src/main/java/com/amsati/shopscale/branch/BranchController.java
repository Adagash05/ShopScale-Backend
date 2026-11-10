package com.amsati.shopscale.branch;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/branches")
@RequiredArgsConstructor
public class BranchController {
    private final BranchService service;

    /** Any signed-in member may list branches (the POS needs it to choose where a sale happens). */
    @GetMapping
    public List<BranchService.BranchDto> list() { return service.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('LOCATIONS_MANAGE')")
    public BranchService.BranchDto create(@Valid @RequestBody BranchService.BranchRequest r) { return service.create(r); }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has('LOCATIONS_MANAGE')")
    public BranchService.BranchDto update(@PathVariable UUID id, @Valid @RequestBody BranchService.BranchRequest r) { return service.update(id, r); }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@access.has('LOCATIONS_MANAGE')")
    public BranchService.BranchDto deactivate(@PathVariable UUID id) { return service.setActive(id, false); }

    @PostMapping("/{id}/activate")
    @PreAuthorize("@access.has('LOCATIONS_MANAGE')")
    public BranchService.BranchDto activate(@PathVariable UUID id) { return service.setActive(id, true); }

    @GetMapping("/stock")
    @PreAuthorize("@access.has('STOCK_VIEW')")
    public List<BranchService.VariantBranchStock> stock(@RequestParam UUID variantId) { return service.stockOfVariant(variantId); }
}
