package com.amsati.shopscale.store;

import com.amsati.shopscale.store.StoreOrderService.*;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Anonymous shoppers. Everything is keyed by the store's slug or the order's secret token; no tenant id is ever accepted from the caller. */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicStoreController {
    private final StoreOrderService orders;

    @GetMapping("/store/{slug}") public StoreInfo info(@PathVariable String slug) { return orders.info(slug); }

    @GetMapping("/store/{slug}/products")
    public List<StoreItem> products(@PathVariable String slug, @RequestParam(required = false) String q) { return orders.catalogue(slug, q); }

    @PostMapping("/store/{slug}/orders")
    public Placed place(@PathVariable String slug, @Valid @RequestBody OrderRequest r) { return orders.place(slug, r); }

    @GetMapping("/orders/{token}") public OrderDto status(@PathVariable String token) { return orders.status(token); }

    @PostMapping("/orders/{token}/verify") public OrderDto verify(@PathVariable String token) { return orders.verify(token); }

    /** Lets the SPA, served on a merchant's verified custom domain, find which store it is. */
    @GetMapping("/domain") public Map<String, String> domain(@RequestParam String host) {
        return orders.slugForDomain(host).map(s -> Map.of("slug", s)).orElseThrow(() -> com.amsati.shopscale.common.ApiException.notFound("Unknown domain"));
    }
}
