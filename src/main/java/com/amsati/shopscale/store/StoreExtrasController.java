package com.amsati.shopscale.store;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.store.StoreExtrasService.*;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Storefront extras. Public calls are anonymous and keyed by the store slug; merchant calls use the signed-in business. */
@RestController
@RequiredArgsConstructor
public class StoreExtrasController {
    private final StoreExtrasService extras;

    // ----- public -----
    @GetMapping("/api/public/store/{slug}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable String slug) {
        LogoFile s = extras.logoOf(slug).orElseThrow(() -> ApiException.notFound("No logo"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(s.type()))
            .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "default-src 'none'")
            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic()).body(s.data());
    }

    @GetMapping("/api/public/store/{slug}/reviews") public List<ReviewDto> reviews(@PathVariable String slug, @RequestParam UUID productId) { return extras.publicReviews(slug, productId); }
    @GetMapping("/api/public/store/{slug}/testimonials") public List<ReviewDto> testimonials(@PathVariable String slug) { return extras.testimonials(slug); }
    @PostMapping("/api/public/store/{slug}/reviews") public Map<String, String> review(@PathVariable String slug, @Valid @RequestBody ReviewRequest r) { return extras.submitReview(slug, r); }
    @PostMapping("/api/public/store/{slug}/subscribe") public Map<String, String> subscribe(@PathVariable String slug, @Valid @RequestBody SubscribeRequest r) { return extras.subscribe(slug, r); }
    @PostMapping("/api/public/store/{slug}/enquiries") public Map<String, String> enquire(@PathVariable String slug, @Valid @RequestBody EnquiryRequest r) { return extras.enquire(slug, r); }
    @PostMapping("/api/public/store/{slug}/orders/lookup") public StoreOrderService.OrderDto lookup(@PathVariable String slug, @Valid @RequestBody LookupRequest r) { return extras.lookup(slug, r); }

    // ----- merchant: profile -----
    @GetMapping("/api/store/profile") @PreAuthorize("@access.has('STORE_VIEW')")
    public ProfileDto profile() { return extras.profile(); }

    @PutMapping("/api/store/profile") @PreAuthorize("@access.has('STORE_EDIT')")
    public ProfileDto saveProfile(@Valid @RequestBody ProfileRequest r) { return extras.saveProfile(r); }

    @PostMapping("/api/store/logo") @PreAuthorize("@access.has('STORE_EDIT')")
    public ProfileDto saveLogo(@RequestParam("file") MultipartFile file) throws IOException { return extras.saveLogo(file.getBytes()); }

    @DeleteMapping("/api/store/logo") @PreAuthorize("@access.has('STORE_EDIT')")
    public ProfileDto removeLogo() { return extras.removeLogo(); }

    // ----- merchant: product storefront details -----
    @GetMapping("/api/store/products/{id}/presentation") @PreAuthorize("@access.has('PRODUCTS_VIEW')")
    public PresentationDto presentation(@PathVariable UUID id) { return extras.presentation(id); }

    @PutMapping("/api/store/products/{id}/presentation") @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    public PresentationDto savePresentation(@PathVariable UUID id, @Valid @RequestBody PresentationRequest r) { return extras.savePresentation(id, r); }

    @PostMapping("/api/store/products/{id}/gallery") @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    public PresentationDto addGallery(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException { return extras.addGallery(id, file.getBytes()); }

    @DeleteMapping("/api/store/products/{id}/gallery/{imageId}") @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    public PresentationDto removeGallery(@PathVariable UUID id, @PathVariable UUID imageId) { return extras.removeGallery(id, imageId); }

    // ----- merchant: inbox -----
    @GetMapping("/api/store/reviews") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public List<ReviewAdminDto> adminReviews() { return extras.adminReviews(); }

    @PostMapping("/api/store/reviews/{id}/status/{to}") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public ReviewAdminDto reviewStatus(@PathVariable UUID id, @PathVariable String to) { return extras.setReviewStatus(id, to); }

    @GetMapping("/api/store/enquiries") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public List<EnquiryDto> enquiries() { return extras.adminEnquiries(); }

    @PostMapping("/api/store/enquiries/{id}/status/{to}") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public EnquiryDto enquiryStatus(@PathVariable UUID id, @PathVariable String to) { return extras.setEnquiryStatus(id, to); }

    @GetMapping("/api/store/subscribers") @PreAuthorize("@access.has('STORE_ORDERS_MANAGE')")
    public List<SubscriberDto> subscribers() { return extras.adminSubscribers(); }
}
