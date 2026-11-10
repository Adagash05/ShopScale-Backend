package com.amsati.shopscale.store;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** The merchant's Paystack account calls this. The signature is checked with that merchant's own secret key. */
@RestController
@RequestMapping("/api/webhooks/paystack")
@RequiredArgsConstructor
public class StoreWebhookController {
    private final StoreOrderService orders;

    @PostMapping("/store/{slug}")
    public ResponseEntity<String> receive(@PathVariable String slug, @RequestBody String body,
                                          @RequestHeader(value = "x-paystack-signature", required = false) String signature) {
        orders.webhook(slug, body, signature);
        return ResponseEntity.ok("ok");
    }
}
