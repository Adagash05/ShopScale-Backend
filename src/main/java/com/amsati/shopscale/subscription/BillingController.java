package com.amsati.shopscale.subscription;

import com.amsati.shopscale.subscription.BillingService.*;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class BillingController {
    private final BillingService billing;

    @GetMapping("/api/subscriptions/quote") @PreAuthorize("@access.has('SUBSCRIPTION_VIEW')")
    public Quote quote(@RequestParam String plan, @RequestParam(required = false) BillingCycle cycle) { return billing.preview(plan, cycle); }

    @GetMapping("/api/subscriptions/billing-status") @PreAuthorize("@access.has('SUBSCRIPTION_VIEW')")
    public Map<String, Boolean> status() { return Map.of("configured", billing.configured()); }

    @PostMapping("/api/subscriptions/checkout") @PreAuthorize("@access.has('SUBSCRIPTION_MANAGE')")
    public Checkout checkout(@RequestBody CheckoutRequest r) { return billing.checkout(r); }

    @PostMapping("/api/subscriptions/verify") @PreAuthorize("@access.has('SUBSCRIPTION_MANAGE')")
    public SubscriptionService.Result verify(@RequestBody Map<String, String> body) { return billing.verify(body.get("reference")); }

    @GetMapping("/api/subscriptions/payments") @PreAuthorize("@access.has('SUBSCRIPTION_VIEW')")
    public List<PaymentDto> history() { return billing.history(); }

    @PostMapping("/api/subscriptions/cancel") @PreAuthorize("@access.has('SUBSCRIPTION_MANAGE')")
    public SubscriptionService.Result cancel() { return billing.cancel(); }

    /** Called by the AMSATI Paystack account. The signature is checked against our platform secret key. */
    @PostMapping("/api/webhooks/paystack/subscriptions")
    public ResponseEntity<String> webhook(@RequestBody String body, @RequestHeader(value = "x-paystack-signature", required = false) String sig) {
        billing.webhook(body, sig);
        return ResponseEntity.ok("ok");
    }
}
