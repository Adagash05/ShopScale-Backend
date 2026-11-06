package com.amsati.shopscale.team;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.auth.User;
import com.amsati.shopscale.auth.UserRepository;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.business.Membership;
import com.amsati.shopscale.business.MembershipRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.DefaultRoles;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Role;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.security.TokenHash;
import com.amsati.shopscale.subscription.SubscriptionService;
import com.amsati.shopscale.team.TeamDtos.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TeamService {
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final CustomRoleRepository customRoles;
    private final InvitationRepository invitations;
    private final BusinessRepository businesses;
    private final AccessService access;
    private final SubscriptionService subscriptions;
    private final AuditService audit;
    private final EmailService email;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;
    @Value("${app.invitation-ttl-days:7}")
    private int ttlDays;

    private record Resolved(Role role, UUID roleId, String name, Set<Permission> permissions) {}

    // ---------- guards ----------
    private void assertCanGrant(Set<Permission> target) {
        if (!DefaultRoles.canGrant(access.current(), target)) {
            throw ApiException.forbidden("You can only give access that you have yourself");
        }
    }

    private void assertCanManage(Membership target) {
        if (target.getUserId().equals(Tenant.userId())) throw ApiException.forbidden("You can't change your own access");
        if (target.getRole() == Role.OWNER) throw ApiException.forbidden("The owner's access can't be changed here");
        if (!DefaultRoles.canGrant(access.current(), access.roleInfo(target).permissions())) {
            throw ApiException.forbidden("You can't manage someone who has more access than you");
        }
    }

    private Resolved resolve(Role role, UUID roleId, UUID bid) {
        if (role == Role.OWNER) throw ApiException.badRequest("Ownership can't be given from here");
        if (role == Role.CUSTOM) {
            if (roleId == null) throw ApiException.badRequest("Choose a custom role");
            CustomRole cr = customRoles.findByIdAndBusinessId(roleId, bid).orElseThrow(() -> ApiException.badRequest("That role does not exist"));
            return new Resolved(Role.CUSTOM, cr.getId(), cr.getName(), Set.copyOf(cr.getPermissions()));
        }
        if (roleId != null) throw ApiException.badRequest("A built-in role does not take a role id");
        DefaultRoles.Def d = DefaultRoles.of(role);
        return new Resolved(role, null, d.name(), d.permissions());
    }

    // ---------- members ----------
    @Transactional(readOnly = true)
    public List<MemberDto> members() {
        UUID bid = Tenant.businessId();
        List<Membership> ms = memberships.findByBusinessIdOrderByCreatedAtAsc(bid);
        Map<UUID, User> us = users.findAllById(ms.stream().map(Membership::getUserId).toList()).stream()
            .collect(Collectors.toMap(User::getId, u -> u));
        Map<UUID, CustomRole> cr = customRoles.findByBusinessIdOrderByNameAsc(bid).stream().collect(Collectors.toMap(CustomRole::getId, r -> r));
        return ms.stream().map(m -> {
            User u = us.get(m.getUserId());
            String roleName = m.getRole() == Role.CUSTOM
                ? (cr.containsKey(m.getRoleId()) ? cr.get(m.getRoleId()).getName() : "Custom role")
                : DefaultRoles.of(m.getRole()).name();
            return new MemberDto(m.getId(), m.getUserId(), u == null ? "Unknown" : u.getFullName(), u == null ? "" : u.getEmail(),
                u == null ? null : u.getPhone(), m.getRole(), m.getRoleId(), roleName, m.isActive(), m.getRole() == Role.OWNER,
                m.getCreatedAt(), m.getLastActiveAt());
        }).toList();
    }

    @Transactional
    public MemberDto changeRole(UUID membershipId, RoleRef ref) {
        UUID bid = Tenant.businessId();
        Membership m = memberships.findByIdAndBusinessId(membershipId, bid).orElseThrow(() -> ApiException.notFound("Team member not found"));
        assertCanManage(m);
        Resolved r = resolve(ref.role(), ref.roleId(), bid);
        assertCanGrant(r.permissions());
        m.setRole(r.role());
        m.setRoleId(r.roleId());
        access.invalidateAll();
        audit.record(bid, Tenant.userId(), "TEAM_ROLE_CHANGED", "membership", membershipId, "now " + r.name());
        return members().stream().filter(x -> x.membershipId().equals(membershipId)).findFirst().orElseThrow();
    }

    @Transactional
    public MemberDto setActive(UUID membershipId, boolean active) {
        UUID bid = Tenant.businessId();
        Membership m = memberships.findByIdAndBusinessId(membershipId, bid).orElseThrow(() -> ApiException.notFound("Team member not found"));
        assertCanManage(m);
        if (active && !m.isActive()) {
            subscriptions.requireUserCapacity(bid, memberships.countByBusinessIdAndActive(bid, true) + 1);
        }
        m.setActive(active);
        access.invalidateAll();
        audit.record(bid, Tenant.userId(), active ? "TEAM_REACTIVATED" : "TEAM_DEACTIVATED", "membership", membershipId, null);
        return members().stream().filter(x -> x.membershipId().equals(membershipId)).findFirst().orElseThrow();
    }

    // ---------- invitations ----------
    private InvitationDto toDto(Invitation i, Map<UUID, CustomRole> cr) {
        String status = "PENDING".equals(i.getStatus()) && i.getExpiresAt().isBefore(Instant.now()) ? "EXPIRED" : i.getStatus();
        String roleName = i.getRole() == Role.CUSTOM ? (cr.containsKey(i.getRoleId()) ? cr.get(i.getRoleId()).getName() : "Custom role")
            : DefaultRoles.of(i.getRole()).name();
        return new InvitationDto(i.getId(), i.getFullName(), i.getEmail(), i.getPhone(), i.getRole(), i.getRoleId(), roleName,
            status, i.getExpiresAt(), i.getCreatedAt(), i.isEmailSent());
    }

    private Map<UUID, CustomRole> customMap(UUID bid) {
        return customRoles.findByBusinessIdOrderByNameAsc(bid).stream().collect(Collectors.toMap(CustomRole::getId, r -> r));
    }

    @Transactional(readOnly = true)
    public List<InvitationDto> listInvitations() {
        UUID bid = Tenant.businessId();
        Map<UUID, CustomRole> cr = customMap(bid);
        return invitations.findByBusinessIdOrderByCreatedAtDesc(bid).stream().map(i -> toDto(i, cr)).toList();
    }

    @Transactional
    public InviteResult invite(InviteRequest r) {
        UUID bid = Tenant.businessId();
        Resolved role = resolve(r.role(), r.roleId(), bid);
        assertCanGrant(role.permissions());
        String mail = r.email().trim().toLowerCase();

        users.findByEmail(mail).flatMap(u -> memberships.findByUserIdAndBusinessIdAndActiveTrue(u.getId(), bid)).ifPresent(x -> {
            throw ApiException.conflict("That person is already on your team");
        });
        if (invitations.existsByBusinessIdAndEmailIgnoreCaseAndStatus(bid, mail, "PENDING")) {
            throw ApiException.conflict("There is already a pending invitation for that email. Resend or revoke it.");
        }
        long after = memberships.countByBusinessIdAndActive(bid, true) + invitations.countByBusinessIdAndStatus(bid, "PENDING") + 1;
        subscriptions.requireUserCapacity(bid, after);

        String raw = TokenHash.newToken();
        Invitation inv = new Invitation();
        inv.setBusinessId(bid);
        inv.setEmail(mail);
        inv.setFullName(r.fullName().trim());
        inv.setPhone(r.phone());
        inv.setRole(role.role());
        inv.setRoleId(role.roleId());
        inv.setTokenHash(TokenHash.sha256(raw));
        inv.setExpiresAt(Instant.now().plus(Duration.ofDays(ttlDays)));
        inv.setInvitedBy(Tenant.userId());
        invitations.save(inv);

        boolean sent = deliver(inv, raw, role.name());
        audit.record(bid, Tenant.userId(), "TEAM_INVITED", "invitation", inv.getId(), mail + " as " + role.name());
        return new InviteResult(toDto(inv, customMap(bid)), link(raw), sent);
    }

    @Transactional
    public InviteResult resend(UUID id) {
        UUID bid = Tenant.businessId();
        Invitation inv = invitations.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Invitation not found"));
        if ("ACCEPTED".equals(inv.getStatus())) throw ApiException.conflict("This invitation was already accepted");
        if ("REVOKED".equals(inv.getStatus())) throw ApiException.conflict("This invitation was cancelled. Send a new one.");
        Resolved role = resolve(inv.getRole(), inv.getRoleId(), bid);
        assertCanGrant(role.permissions());
        String raw = TokenHash.newToken();
        inv.setTokenHash(TokenHash.sha256(raw));      // the old link stops working
        inv.setStatus("PENDING");
        inv.setExpiresAt(Instant.now().plus(Duration.ofDays(ttlDays)));
        boolean sent = deliver(inv, raw, role.name());
        audit.record(bid, Tenant.userId(), "TEAM_INVITE_RESENT", "invitation", id, inv.getEmail());
        return new InviteResult(toDto(inv, customMap(bid)), link(raw), sent);
    }

    @Transactional
    public InvitationDto revoke(UUID id) {
        UUID bid = Tenant.businessId();
        Invitation inv = invitations.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Invitation not found"));
        if ("ACCEPTED".equals(inv.getStatus())) throw ApiException.conflict("This invitation was already accepted");
        inv.setStatus("REVOKED");
        audit.record(bid, Tenant.userId(), "TEAM_INVITE_REVOKED", "invitation", id, inv.getEmail());
        return toDto(inv, customMap(bid));
    }

    private String link(String raw) { return frontendUrl.replaceAll("/+$", "") + "/accept-invite?token=" + raw; }

    private boolean deliver(Invitation inv, String raw, String roleName) {
        Business b = businesses.findById(inv.getBusinessId()).orElseThrow();
        String body = "Hello " + inv.getFullName() + ",\n\nYou have been invited to join " + b.getName() + " on ShopScale as "
            + roleName + ".\n\nAccept your invitation:\n" + link(raw) + "\n\nThis link works once and expires on "
            + inv.getExpiresAt().toString().substring(0, 10) + ". If you were not expecting this, ignore this email.\n\nShopScale by AMSATI";
        boolean sent = email.send(inv.getEmail(), "You're invited to " + b.getName() + " on ShopScale", body);
        inv.setEmailSent(sent);
        return sent;
    }

    // ---------- roles ----------
    @Transactional(readOnly = true)
    public List<RoleDto> roles() {
        UUID bid = Tenant.businessId();
        List<Membership> ms = memberships.findByBusinessIdOrderByCreatedAtAsc(bid).stream().filter(Membership::isActive).toList();
        List<RoleDto> out = new ArrayList<>();
        for (DefaultRoles.Def d : DefaultRoles.all()) {
            out.add(new RoleDto(d.role().name(), null, d.role(), d.name(), d.description(), true,
                d.permissions().stream().map(Enum::name).sorted().toList(), ms.stream().filter(m -> m.getRole() == d.role()).count()));
        }
        for (CustomRole c : customRoles.findByBusinessIdOrderByNameAsc(bid)) {
            out.add(new RoleDto(c.getId().toString(), c.getId(), Role.CUSTOM, c.getName(), c.getDescription(), false,
                c.getPermissions().stream().map(Enum::name).sorted().toList(),
                ms.stream().filter(m -> c.getId().equals(m.getRoleId())).count()));
        }
        return out;
    }

    public List<PermissionGroup> catalogue() {
        Map<String, List<PermissionItem>> g = new LinkedHashMap<>();
        for (Permission p : Permission.values()) g.computeIfAbsent(p.module(), k -> new ArrayList<>()).add(new PermissionItem(p.name(), p.label()));
        return g.entrySet().stream().map(e -> new PermissionGroup(e.getKey(), e.getValue())).toList();
    }

    @Transactional
    public RoleDto createRole(RoleRequest r) {
        UUID bid = Tenant.businessId();
        assertCanGrant(r.permissions());
        String name = r.name().trim();
        if (customRoles.existsByBusinessIdAndNameIgnoreCase(bid, name)
            || DefaultRoles.all().stream().anyMatch(d -> d.name().equalsIgnoreCase(name))) {
            throw ApiException.conflict("A role with that name already exists");
        }
        CustomRole c = new CustomRole();
        c.setBusinessId(bid);
        c.setName(name);
        c.setDescription(r.description());
        c.setPermissions(EnumSet.copyOf(r.permissions()));
        customRoles.save(c);
        audit.record(bid, Tenant.userId(), "ROLE_CREATED", "role", c.getId(), name);
        return roles().stream().filter(x -> c.getId().equals(x.id())).findFirst().orElseThrow();
    }

    @Transactional
    public RoleDto updateRole(UUID id, RoleRequest r) {
        UUID bid = Tenant.businessId();
        CustomRole c = customRoles.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Role not found"));
        assertCanGrant(c.getPermissions());   // can't edit a role that is more powerful than you
        assertCanGrant(r.permissions());
        String name = r.name().trim();
        boolean renamed = !c.getName().equalsIgnoreCase(name);
        if (renamed && (customRoles.existsByBusinessIdAndNameIgnoreCase(bid, name)
            || DefaultRoles.all().stream().anyMatch(d -> d.name().equalsIgnoreCase(name)))) {
            throw ApiException.conflict("A role with that name already exists");
        }
        c.setName(name);
        c.setDescription(r.description());
        c.setPermissions(EnumSet.copyOf(r.permissions()));
        access.invalidateAll();
        audit.record(bid, Tenant.userId(), "ROLE_UPDATED", "role", id, name);
        return roles().stream().filter(x -> id.equals(x.id())).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteRole(UUID id) {
        UUID bid = Tenant.businessId();
        CustomRole c = customRoles.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Role not found"));
        if (memberships.countByBusinessIdAndRoleId(bid, id) > 0 || invitations.countByBusinessIdAndRoleId(bid, id) > 0) {
            throw ApiException.conflict("This role is still in use. Move those people to another role first.");
        }
        customRoles.delete(c);
        audit.record(bid, Tenant.userId(), "ROLE_DELETED", "role", id, c.getName());
    }
}
