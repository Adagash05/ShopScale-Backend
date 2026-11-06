package com.amsati.shopscale.inventory;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {
    private final InventoryService service;

    @PostMapping("/adjust")
    @PreAuthorize("@access.has('STOCK_ADJUST') or @access.has('STOCK_RESTOCK')")
    public InventoryService.AdjustResult adjust(@Valid @RequestBody InventoryService.AdjustRequest r) { return service.adjust(r); }

    @GetMapping("/movements")
    @PreAuthorize("@access.has('STOCK_MOVEMENTS_VIEW')")
    public Page<MovementDto> movements(@RequestParam(required = false) UUID variantId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "30") int size) {
        return service.history(variantId, page, size);
    }

    @GetMapping("/low-stock")
    @PreAuthorize("@access.has('STOCK_VIEW')")
    public List<LowStockDto> lowStock() { return service.lowStock(); }
}
