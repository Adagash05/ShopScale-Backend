package com.amsati.shopscale.industry;

import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.common.ApiException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Server-side gate for industry-specific endpoints, so a retail shop cannot call kitchen or catering APIs. */
@Component
@RequiredArgsConstructor
public class IndustryGuard {
    private final BusinessRepository businesses;

    public void require(UUID businessId, Workflow w) {
        Business b = businesses.findById(businessId).orElseThrow(() -> ApiException.notFound("Business not found"));
        if (!Workflow.forType(b.getBusinessType()).contains(w)) {
            throw ApiException.forbidden("This feature is not part of your business type.");
        }
    }

    /** Passes when the business type includes at least one of the workflows. */
    public void requireAny(UUID businessId, Workflow... any) {
        Business b = businesses.findById(businessId).orElseThrow(() -> ApiException.notFound("Business not found"));
        java.util.Set<Workflow> have = Workflow.forType(b.getBusinessType());
        for (Workflow w : any) if (have.contains(w)) return;
        throw ApiException.forbidden("This feature is not part of your business type.");
    }
}
