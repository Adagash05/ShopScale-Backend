package com.amsati.shopscale.subscription;

import com.amsati.shopscale.security.Tenant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {
    private final PlanRepository plans;
    private final SubscriptionService service;

    public record PlanDto(String code, String name, BigDecimal monthlyPrice, BigDecimal annualPrice,
                          int maxUsers, int maxLocations, Integer maxItems, Set<String> features) {}

    public record CurrentDto(String planCode, String planName, String status, BillingCycle billingCycle,
                             Instant periodEnd, boolean writable, int maxUsers, Integer maxItems, Set<String> features) {}

    @GetMapping("/plans")
    public List<PlanDto> plans() {
        return plans.findByActiveTrueOrderBySortOrderAsc().stream()
            .map(p -> new PlanDto(p.getCode(), p.getName(), p.getMonthlyPrice(), p.getAnnualPrice(),
                p.getMaxUsers(), p.getMaxLocations(), p.getMaxItems(), new TreeSet<>(p.getFeatures())))
            .toList();
    }

    @GetMapping("/current")
    public CurrentDto current() {
        SubscriptionState s = service.state(Tenant.businessId());
        return new CurrentDto(s.planCode(), s.planName(), s.status(), s.billingCycle(), s.periodEnd(),
            s.writable(), s.maxUsers(), s.maxItems(), new TreeSet<>(s.features()));
    }
}
