package com.amsati.shopscale.partners;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** The signed-in partner's own portal. Every method works on the partner record of the caller's account; no id of another partner is ever accepted. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/partners")
@PreAuthorize("@partners.member()")
public class PartnerController {
    private final AffiliateService affiliates;
    private final PartnerQueryService queries;
    private final WithdrawalService withdrawals;

    @GetMapping("/me")
    public Map<String, Object> me() {
        var m = affiliates.mine();
        return m == null ? Map.of("applied", false) : Map.of("applied", true, "partner", m);
    }

    @PostMapping("/apply")
    public AffiliateService.Dto apply(@Valid @RequestBody AffiliateService.ApplyRequest r) { return affiliates.apply(r); }

    @PutMapping("/profile")
    public AffiliateService.Dto profile(@Valid @RequestBody AffiliateService.ProfileUpdate r) { return affiliates.updateProfile(r); }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() { return queries.dashboard(); }

    @GetMapping("/referrals")
    public List<Map<String, Object>> referrals(@RequestParam(defaultValue = "200") int limit) { return queries.myReferrals(limit); }

    @GetMapping("/commissions")
    public List<Map<String, Object>> commissions(@RequestParam(defaultValue = "200") int limit) { return queries.myCommissions(limit); }

    @GetMapping("/withdrawals")
    public List<WithdrawalService.Dto> withdrawals() { return withdrawals.mine(); }

    @PostMapping("/withdrawals")
    public WithdrawalService.Dto requestWithdrawal() { return withdrawals.request(); }

    @PostMapping("/withdrawals/{id}/cancel")
    public WithdrawalService.Dto cancel(@PathVariable UUID id) { return withdrawals.cancel(id); }

    @GetMapping("/bank-account")
    public Map<String, Object> bank() {
        var b = affiliates.bank();
        return b == null ? Map.of("saved", false) : Map.of("saved", true, "bank", b);
    }

    @PutMapping("/bank-account")
    public AffiliateService.BankDto saveBank(@Valid @RequestBody AffiliateService.BankRequest r) { return affiliates.saveBank(r); }

    @GetMapping("/resources")
    public List<AffiliateService.Resource> resources() { affiliates.requireApproved(); return affiliates.resources(false); }
}
