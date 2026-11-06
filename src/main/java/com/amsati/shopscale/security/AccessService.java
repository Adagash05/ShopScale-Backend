package com.amsati.shopscale.security;

import com.amsati.shopscale.business.Membership;
import com.amsati.shopscale.business.MembershipRepository;
import com.amsati.shopscale.team.CustomRole;
import com.amsati.shopscale.team.CustomRoleRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Server-side authorization. The JWT only says who you are and which business you picked; what you may do is looked
 * up from the database (active membership + role) so deactivation and role changes take effect immediately on this
 * instance and within CACHE_MS on others. Use as: @PreAuthorize("@access.has('PRODUCTS_VIEW')").
 */
@Component("access")
@RequiredArgsConstructor
public class AccessService {
    private static final long CACHE_MS = 15_000;

    private final MembershipRepository memberships;
    private final CustomRoleRepository customRoles;

    private record Cached(Set<Permission> permissions, long at) {}
    public record RoleInfo(String name, Set<Permission> permissions) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public boolean has(String permission) {
        Permission p;
        try { p = Permission.valueOf(permission); } catch (IllegalArgumentException e) { return false; }
        return current().contains(p);
    }

    public void require(Permission p) {
        if (!current().contains(p)) throw new AccessDeniedException("Missing permission " + p);
    }

    /** Permissions of the signed-in user in the business named by their token. Empty if not an active member. */
    public Set<Permission> current() {
        AuthPrincipal p = Tenant.principal();
        if (p.businessId() == null) return Set.of();
        return permissionsOf(p.userId(), p.businessId());
    }

    public Set<Permission> permissionsOf(UUID userId, UUID businessId) {
        String key = userId + ":" + businessId;
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && now - c.at() < CACHE_MS) return c.permissions();
        Set<Permission> perms = memberships.findByUserIdAndBusinessIdAndActiveTrue(userId, businessId)
            .map(m -> roleInfo(m).permissions()).orElse(Set.of());
        cache.put(key, new Cached(perms, now));
        return perms;
    }

    public RoleInfo roleInfo(Membership m) {
        if (m.getRole() == Role.CUSTOM) {
            Optional<CustomRole> r = m.getRoleId() == null ? Optional.empty()
                : customRoles.findByIdAndBusinessId(m.getRoleId(), m.getBusinessId());
            return r.map(x -> new RoleInfo(x.getName(), Set.copyOf(x.getPermissions()))).orElse(new RoleInfo("Custom role", Set.of()));
        }
        DefaultRoles.Def d = DefaultRoles.of(m.getRole());
        return new RoleInfo(d.name(), d.permissions());
    }

    public Set<String> names(Set<Permission> perms) {
        Set<String> out = new TreeSet<>();
        perms.forEach(p -> out.add(p.name()));
        return out;
    }

    /** Call after any change to memberships or roles. */
    public void invalidateAll() { cache.clear(); }
}
