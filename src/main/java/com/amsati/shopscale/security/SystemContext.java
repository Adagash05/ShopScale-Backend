package com.amsati.shopscale.security;

import com.amsati.shopscale.business.Membership;
import com.amsati.shopscale.business.MembershipRepository;
import com.amsati.shopscale.common.ApiException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Runs trusted server work for ONE business with no signed-in user (a verified online payment, a verified Paystack
 * webhook). The business id always comes from a database row we loaded ourselves, never from request input.
 */
@Component
@RequiredArgsConstructor
public class SystemContext {
    private final MembershipRepository memberships;

    public <T> T asBusiness(UUID businessId, Supplier<T> work) {
        Membership owner = memberships.findByBusinessIdOrderByCreatedAtAsc(businessId).stream()
            .filter(m -> m.getRole() == Role.OWNER && m.isActive()).findFirst()
            .orElseThrow(() -> ApiException.conflict("This business has no active owner"));
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        AuthPrincipal p = new AuthPrincipal(owner.getUserId(), "", businessId, Role.OWNER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, List.of()));
        try {
            return work.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }
}
