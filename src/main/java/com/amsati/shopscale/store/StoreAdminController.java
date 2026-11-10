package com.amsati.shopscale.store;

import com.amsati.shopscale.store.StoreOrderService.*;
import com.amsati.shopscale.store.StoreSettingsService.*;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Merchant side: settings, their own Paystack connection, domain, and online order management. */
@RestController
@RequestMapping("/api/store")
@RequiredArgsConstructor
public class StoreAdminController {
    private final StoreSettingsService settings;
    private final StoreOrderService orders;

    @GetMapping("/settings") @PreAuthorize("@access.has('STORE_VIEW')")
    public SettingsDto get() { return settings.get(); }

    @PutMapping("/settings") @PreAuthorize("@access.has('STORE_EDIT')")
    public SettingsDto save(@Valid @RequestBody SettingsRequest r) { return settings.update(r); }

    @PutMapping("/paystack") @PreAuthorize("@access.has('STORE_PAYMENTS_MANAGE')")
    public SettingsDto connect(@Valid @RequestBody PaystackRequest r) { return settings.connectPaystack(r); }

    @DeleteMapping("/paystack") @PreAuthorize("@access.has('STORE_PAYMENTS_MANAGE')")
    public SettingsDto disconnect() { return settings.disconnectPaystack(); }

    @PutMapping("/domain") @PreAuthorize("@access.has('STORE_EDIT')")
    public SettingsDto domain(@Valid @RequestBody DomainRequest r) { return settings.setDomain(r); }

    @DeleteMapping("/domain") @PreAuthorize("@access.has('STORE_EDIT')")
    public SettingsDto removeDomain() { return settings.removeDomain(); }

    @PostMapping("/domain/verify") @PreAuthorize("@access.has('STORE_EDIT')")
    public SettingsDto verifyDomain() { return settings.verifyDomain(); }

    @GetMapping("/orders") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public List<OrderDto> list(@RequestParam(required = false) String status) { return orders.list(status); }

    @GetMapping("/orders/counts") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public Map<String, Long> counts() { return orders.counts(); }

    @GetMapping("/orders/{id}") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public OrderDto one(@PathVariable UUID id) { return orders.get(id); }

    @PostMapping("/orders/{id}/status/{to}") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public OrderDto status(@PathVariable UUID id, @PathVariable String to) { return orders.advance(id, to); }

    @PostMapping("/orders/{id}/complete") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public OrderDto complete(@PathVariable UUID id, @RequestBody(required = false) CompleteRequest r) { return orders.complete(id, r); }

    @PostMapping("/orders/{id}/cancel") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public OrderDto cancel(@PathVariable UUID id) { return orders.cancel(id); }

    @PostMapping("/orders/{id}/dismiss-alert") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public OrderDto dismiss(@PathVariable UUID id) { return orders.clearAttention(id); }
}
