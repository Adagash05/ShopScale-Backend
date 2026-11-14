package com.amsati.shopscale.partners;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** ShopScale Partners administration. Only platform administrators (see PartnerAccess); business roles do not reach any of this. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/partners")
@PreAuthorize("@partners.admin()")
public class PartnerAdminController {
    private final AffiliateService affiliates;
    private final PartnerQueryService queries;
    private final CommissionService commissions;
    private final WithdrawalService withdrawals;
    private final PartnerSettingsService settings;

    /** Lets the screens know whether to show the Partners administration at all. */
    @GetMapping("/access")
    public Map<String, Boolean> access() { return Map.of("admin", true); }

    @GetMapping("/overview")
    public Map<String, Object> overview() { return queries.overview(); }

    @GetMapping("/performance")
    public List<Map<String, Object>> performance(@RequestParam(defaultValue = "100") int limit) { return queries.performance(limit); }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "200") int limit) { return queries.audit(limit); }

    // ----- applications and partners
    @GetMapping("/affiliates")
    public List<AffiliateService.Dto> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q, @RequestParam(defaultValue = "200") int limit) { return affiliates.list(status, q, limit); }

    @GetMapping("/affiliates/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) { return queries.affiliateDetail(id); }

    public record Reason(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 3, max = 500) String reason) {}

    @PostMapping("/affiliates/{id}/approve")
    public AffiliateService.Dto approve(@PathVariable UUID id) { return affiliates.approve(id); }

    @PostMapping("/affiliates/{id}/reject")
    public AffiliateService.Dto reject(@PathVariable UUID id, @Valid @RequestBody Reason r) { return affiliates.reject(id, r.reason()); }

    @PostMapping("/affiliates/{id}/suspend")
    public AffiliateService.Dto suspend(@PathVariable UUID id, @Valid @RequestBody Reason r) { return affiliates.suspend(id, r.reason()); }

    @PostMapping("/affiliates/{id}/reactivate")
    public AffiliateService.Dto reactivate(@PathVariable UUID id) { return affiliates.reactivate(id); }

    // ----- referrals and commissions
    @GetMapping("/referrals")
    public List<Map<String, Object>> referrals(@RequestParam(required = false) UUID affiliateId, @RequestParam(defaultValue = "300") int limit) { return queries.adminReferrals(affiliateId, limit); }

    @GetMapping("/commissions")
    public List<Map<String, Object>> ledger(@RequestParam(required = false) UUID affiliateId, @RequestParam(required = false) String status, @RequestParam(required = false) String customer,
                                            @RequestParam(required = false) String payment, @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(defaultValue = "300") int limit) {
        return queries.ledger(affiliateId, status, customer, payment, from, to, limit);
    }

    @PostMapping("/commissions/adjust")
    public Map<String, UUID> adjust(@Valid @RequestBody CommissionService.AdjustRequest r) { return Map.of("id", commissions.adjust(r)); }

    @PostMapping("/commissions/{id}/reverse")
    public Map<String, Boolean> reverse(@PathVariable UUID id, @Valid @RequestBody CommissionService.ReverseRequest r) { commissions.adminReverse(id, r.reason().trim()); return Map.of("reversed", true); }

    /** For a refund made outside Paystack's notices (for example from the Paystack dashboard). */
    @PostMapping("/refunds")
    public Map<String, Boolean> refund(@Valid @RequestBody CommissionService.RefundRequest r) { commissions.recordRefund(r); return Map.of("recorded", true); }

    // ----- withdrawals
    @GetMapping("/withdrawals")
    public List<WithdrawalService.Dto> withdrawals(@RequestParam(required = false) String status, @RequestParam(required = false) UUID affiliateId, @RequestParam(required = false) String q, @RequestParam(defaultValue = "300") int limit) {
        return withdrawals.list(status, affiliateId, q, limit);
    }

    @PostMapping("/withdrawals/{id}/approve")
    public WithdrawalService.Dto approveW(@PathVariable UUID id) { return withdrawals.approve(id); }

    @PostMapping("/withdrawals/{id}/reject")
    public WithdrawalService.Dto rejectW(@PathVariable UUID id, @Valid @RequestBody WithdrawalService.RejectRequest r) { return withdrawals.reject(id, r.reason()); }

    @PostMapping("/withdrawals/{id}/processing")
    public WithdrawalService.Dto processing(@PathVariable UUID id) { return withdrawals.markProcessing(id); }

    @PostMapping("/withdrawals/{id}/paid")
    public WithdrawalService.Dto paid(@PathVariable UUID id, @Valid @RequestBody WithdrawalService.PaidRequest r) { return withdrawals.markPaid(id, r); }

    @PostMapping("/withdrawals/{id}/failed")
    public WithdrawalService.Dto failed(@PathVariable UUID id, @Valid @RequestBody WithdrawalService.RejectRequest r) { return withdrawals.markFailed(id, r.reason()); }

    @GetMapping("/withdrawals/{id}/bank-details")
    public Map<String, String> bankDetails(@PathVariable UUID id) { return withdrawals.bankDetails(id); }

    // ----- settings and resources
    @GetMapping("/settings")
    public Programme settings() { return settings.get(); }

    @PutMapping("/settings")
    public Programme updateSettings(@Valid @RequestBody PartnerSettingsService.UpdateRequest r) { return settings.update(r); }

    @GetMapping("/resources")
    public List<AffiliateService.Resource> resources() { return affiliates.resources(true); }

    @PostMapping("/resources")
    public AffiliateService.Resource createResource(@Valid @RequestBody AffiliateService.ResourceRequest r) { return affiliates.saveResource(null, r); }

    @PutMapping("/resources/{id}")
    public AffiliateService.Resource updateResource(@PathVariable UUID id, @Valid @RequestBody AffiliateService.ResourceRequest r) { return affiliates.saveResource(id, r); }

    @DeleteMapping("/resources/{id}")
    public Map<String, Boolean> deleteResource(@PathVariable UUID id) { affiliates.deleteResource(id); return Map.of("deleted", true); }

    // ----- CSV
    @GetMapping("/export/{kind}")
    public ResponseEntity<String> export(@PathVariable String kind) {
        String body = queries.export(kind);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"partners-" + kind + ".csv\"").body("﻿" + body);
    }
}
