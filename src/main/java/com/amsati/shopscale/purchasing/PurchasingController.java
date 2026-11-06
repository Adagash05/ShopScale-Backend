package com.amsati.shopscale.purchasing;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PurchasingController {
    private final PurchasingService service;

    @GetMapping("/api/suppliers")
    @PreAuthorize("@access.has('SUPPLIERS_VIEW')")
    public Page<PurchasingService.SupplierDto> suppliers(@RequestParam(required = false) String q,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size) {
        return service.listSuppliers(q, page, size);
    }

    @PostMapping("/api/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('SUPPLIERS_MANAGE')")
    public PurchasingService.SupplierDto createSupplier(@Valid @RequestBody PurchasingService.SupplierRequest r) { return service.createSupplier(r); }

    @GetMapping("/api/suppliers/{id}")
    @PreAuthorize("@access.has('SUPPLIERS_VIEW')")
    public PurchasingService.SupplierDetail supplier(@PathVariable UUID id) { return service.supplierDetail(id); }

    @PutMapping("/api/suppliers/{id}")
    @PreAuthorize("@access.has('SUPPLIERS_MANAGE')")
    public PurchasingService.SupplierDto updateSupplier(@PathVariable UUID id, @Valid @RequestBody PurchasingService.SupplierRequest r) {
        return service.updateSupplier(id, r);
    }

    @PostMapping("/api/suppliers/{id}/payments")
    @PreAuthorize("@access.has('SUPPLIER_PAYMENTS_RECORD')")
    public PurchasingService.SupplierDetail pay(@PathVariable UUID id, @Valid @RequestBody PurchasingService.SupplierPaymentRequest r) {
        return service.paySupplier(id, r);
    }

    @PostMapping("/api/purchases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('PURCHASES_CREATE')")
    public PurchasingService.PurchaseDto receive(@Valid @RequestBody PurchasingService.PurchaseRequest r) { return service.receive(r); }

    @GetMapping("/api/purchases")
    @PreAuthorize("@access.has('PURCHASES_VIEW')")
    public Page<PurchaseSummary> purchases(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return service.listPurchases(page, size);
    }

    @GetMapping("/api/purchases/{id}")
    @PreAuthorize("@access.has('PURCHASES_VIEW')")
    public PurchasingService.PurchaseDto purchase(@PathVariable UUID id) { return service.getPurchase(id); }
}
