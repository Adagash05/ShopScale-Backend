package com.amsati.shopscale.security;

import com.amsati.shopscale.common.ApiException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The only source of tenant identity: the verified JWT. Never read a tenant id from request input. */
public final class Tenant {
    private Tenant() {}

    public static AuthPrincipal principal() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof AuthPrincipal p)) {
            throw ApiException.unauthorized("Sign in to continue");
        }
        return p;
    }

    public static UUID userId() { return principal().userId(); }

    public static UUID businessId() {
        UUID id = principal().businessId();
        if (id == null) throw ApiException.forbidden("Finish setting up your business first");
        return id;
    }
}
