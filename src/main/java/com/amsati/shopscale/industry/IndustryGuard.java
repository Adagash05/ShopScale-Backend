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
}
