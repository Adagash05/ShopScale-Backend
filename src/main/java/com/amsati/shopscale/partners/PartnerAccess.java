package com.amsati.shopscale.partners;

import com.amsati.shopscale.auth.UserRepository;
import com.amsati.shopscale.security.AuthPrincipal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who may use the Partners screens. Referenced from @PreAuthorize as {@code @partners.admin()} / {@code @partners.member()}.
 *
 * ShopScale had no platform-level administrator (every role belongs to one business), so administrators are the verified,
 * active accounts whose email is listed in PLATFORM_ADMIN_EMAILS. The list is server configuration: it cannot be changed from any screen
 * or request. Business roles (owner, manager ...) give no access to the Partners administration.
 */
@Component("partners")
public class PartnerAccess {
    private final Set<String> adminEmails;
    private final UserRepository users;

    public PartnerAccess(@Value("${app.partners.admin-emails:}") String emails, UserRepository users) {
        this.adminEmails = Arrays.stream(emails.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.users = users;
    }

    private com.amsati.shopscale.superadmin.SuperAdminAccess superAdmin;

    /** The Super Admin Console runs the same partner administration with its own token. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSuperAdmin(com.amsati.shopscale.superadmin.SuperAdminAccess s) { this.superAdmin = s; }

    public Set<String> adminEmails() { return adminEmails; }

    /** Any signed-in account (affiliate endpoints then check the account's own partner record). */
    public boolean member() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof AuthPrincipal;
    }

    public boolean admin() {
        if (superAdmin != null && superAdmin.ok()) return true;
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof AuthPrincipal p) || p.email() == null) return false;
        if (!adminEmails.contains(p.email().trim().toLowerCase(Locale.ROOT))) return false;
        // The token's email claim is only trusted together with the account's current state.
        return users.findById(p.userId()).filter(u -> u.isActive() && u.isEmailVerified() && u.getEmail().equalsIgnoreCase(p.email())).isPresent();
    }
}
