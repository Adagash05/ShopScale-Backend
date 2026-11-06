package com.amsati.shopscale.team;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.auth.AuthResponse;
import com.amsati.shopscale.auth.AuthService;
import com.amsati.shopscale.auth.User;
import com.amsati.shopscale.auth.UserRepository;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.business.Membership;
import com.amsati.shopscale.business.MembershipRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.DefaultRoles;
import com.amsati.shopscale.security.Role;
import com.amsati.shopscale.security.TokenHash;
import com.amsati.shopscale.subscription.SubscriptionService;
import com.amsati.shopscale.team.TeamDtos.AcceptRequest;
import com.amsati.shopscale.team.TeamDtos.InvitePreview;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The public half of invitations: look at a link, then accept it by creating or confirming an account. */
@Service
@RequiredArgsConstructor
public class InvitationService {
    private final InvitationRepository invitations;
    private final BusinessRepository businesses;
    private final CustomRoleRepository customRoles;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final AccessService access;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    private Invitation find(String token) {
        Invitation inv = invitations.findByTokenHash(TokenHash.sha256(token))
            .orElseThrow(() -> ApiException.notFound("This invitation link is not valid"));
        if ("ACCEPTED".equals(inv.getStatus())) throw ApiException.conflict("This invitation has already been used");
        if ("REVOKED".equals(inv.getStatus())) throw ApiException.conflict("This invitation was cancelled. Ask your employer for a new one.");
        if (inv.getExpiresAt().isBefore(Instant.now())) throw ApiException.conflict("This invitation has expired. Ask your employer to resend it.");
        return inv;
    }

    private String roleName(Invitation i) {
        if (i.getRole() == Role.CUSTOM) {
            return customRoles.findByIdAndBusinessId(i.getRoleId(), i.getBusinessId()).map(CustomRole::getName).orElse("Custom role");
        }
        return DefaultRoles.of(i.getRole()).name();
    }

    @Transactional(readOnly = true)
    public InvitePreview preview(String token) {
        Invitation inv = find(token);
        Business b = businesses.findById(inv.getBusinessId()).orElseThrow(() -> ApiException.notFound("This invitation link is not valid"));
        return new InvitePreview(b.getName(), inv.getFullName(), inv.getEmail(), roleName(inv), users.existsByEmail(inv.getEmail()));
    }

    @Transactional
    public AuthResponse accept(AcceptRequest r) {
        Invitation inv = find(r.token());
        UUID bid = inv.getBusinessId();

        User user = users.findByEmail(inv.getEmail()).orElse(null);
        if (user != null) {
            if (!user.isActive() || !encoder.matches(r.password(), user.getPasswordHash())) {
                throw ApiException.unauthorized("Enter the password of your existing ShopScale account");
            }
        } else {
            user = new User();
            user.setEmail(inv.getEmail());
            user.setPasswordHash(encoder.encode(r.password()));
            user.setFullName(r.fullName() == null || r.fullName().isBlank() ? inv.getFullName() : r.fullName().trim());
            user.setPhone(inv.getPhone());
            users.save(user);
        }

        Membership m = memberships.findByUserIdAndBusinessId(user.getId(), bid).orElse(null);
        if (m != null && m.isActive()) throw ApiException.conflict("You are already part of this business");
        subscriptions.requireUserCapacity(bid, memberships.countByBusinessIdAndActive(bid, true) + 1);
        if (m == null) {
            m = new Membership();
            m.setUserId(user.getId());
            m.setBusinessId(bid);
        }
        m.setRole(inv.getRole());
        m.setRoleId(inv.getRoleId());
        m.setInvitedBy(inv.getInvitedBy());
        m.setActive(true);
        memberships.save(m);

        inv.setStatus("ACCEPTED");
        inv.setAcceptedAt(Instant.now());
        access.invalidateAll();
        audit.record(bid, user.getId(), "TEAM_JOINED", "membership", m.getId(), inv.getEmail());
        return auth.issue(user, m, UUID.randomUUID());
    }
}
