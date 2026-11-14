package com.amsati.shopscale.partners;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Open endpoints for the Partners landing page and referral link tracking. Nothing here reveals who the partners are. */
@RestController
@RequiredArgsConstructor
public class PartnerPublicController {
    private final PartnerSettingsService settings;
    private final AttributionService attribution;

    public record ClickRequest(@NotBlank @Size(max = 24) String code, @NotBlank @Size(max = 64) String visitorId, @Size(max = 120) String source) {}

    /** Programme terms shown on the public page and in the calculator. */
    @GetMapping("/api/public/partners/programme")
    public Map<String, Object> programme() { return settings.publicView(); }

    /** Always 204: a visitor (or a script) learns nothing about which codes exist. */
    @PostMapping("/api/public/partners/click")
    public ResponseEntity<Void> click(@Valid @RequestBody ClickRequest r) {
        attribution.recordClick(r.code(), r.visitorId(), r.source());
        return ResponseEntity.noContent().build();
    }
}
