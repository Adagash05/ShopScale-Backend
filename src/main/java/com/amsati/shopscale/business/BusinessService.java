package com.amsati.shopscale.business;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.auth.AuthResponse;
import com.amsati.shopscale.auth.AuthService;
import com.amsati.shopscale.auth.User;
import com.amsati.shopscale.auth.UserRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.Role;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BusinessService {
    private final BusinessRepository businesses;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final SubscriptionService subscriptions;
    private final AuthService auth;
    private final AuditService audit;

    @Value("${app.business.enabled-types:RETAIL}")
    private String enabledTypes;

    public record CreateRequest(
        @NotBlank @Size(max = 150) String name,
        @NotNull BusinessType businessType,
        @Size(max = 60) String subtype,
        @NotBlank @Size(min = 3, max = 40) String slug,
        @Size(max = 1000) String description,
        @Size(max = 40) String phone,
        @Size(max = 255) String email,
        @Size(max = 500) String address,
        @Size(min = 3, max = 3) String currency,
        @Size(max = 60) String timezone) {}

    public record UpdateRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 60) String subtype,
        @Size(max = 1000) String description,
        @Size(max = 40) String phone,
        @Size(max = 255) String email,
        @Size(max = 500) String address,
        @Size(max = 60) String timezone,
        boolean allowNegativeStock) {}

    public record SlugCheck(String slug, boolean available, String reason) {}

    private Set<BusinessType> enabled() {
        return Arrays.stream(enabledTypes.split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .map(BusinessType::valueOf).collect(Collectors.toSet());
    }

    public SlugCheck checkSlug(String raw) {
        String slug = SlugRules.normalize(raw);
        if (!SlugRules.isValidFormat(slug))
            return new SlugCheck(slug, false, "Use 3 to 40 letters, numbers or single hyphens, starting and ending with a letter or number");
        if (SlugRules.isReserved(slug)) return new SlugCheck(slug, false, "That name is reserved. Try another.");
        if (businesses.existsBySlug(slug)) return new SlugCheck(slug, false, "That store address is already taken");
        return new SlugCheck(slug, true, null);
    }

    private String validZone(String tz) {
        if (tz == null || tz.isBlank()) return "Africa/Lagos";
        try { return ZoneId.of(tz).getId(); } catch (DateTimeException e) { throw ApiException.badRequest("Unknown timezone"); }
    }

    @Transactional
    public AuthResponse create(CreateRequest r) {
        UUID uid = Tenant.userId();
        if (memberships.existsByUserIdAndActiveTrue(uid)) throw ApiException.conflict("You already have a business on this account");
        if (!enabled().contains(r.businessType())) {
            throw ApiException.badRequest("Tools for this business type are coming soon. Choose Retail for now.");
        }
        SlugCheck check = checkSlug(r.slug());
        if (!check.available()) throw ApiException.badRequest(check.reason());

        Business b = new Business();
        b.setName(r.name().trim());
        b.setSlug(check.slug());
        b.setBusinessType(r.businessType());
        b.setSubtype(r.subtype());
        b.setDescription(r.description());
        b.setPhone(r.phone());
        b.setEmail(r.email());
        b.setAddress(r.address());
        b.setCurrency(r.currency() == null ? "NGN" : r.currency().toUpperCase());
        b.setTimezone(validZone(r.timezone()));
        businesses.save(b);

        Membership m = new Membership();
        m.setUserId(uid);
        m.setBusinessId(b.getId());
        m.setRole(Role.OWNER);
        memberships.save(m);

        subscriptions.startTrial(b.getId());
        audit.record(b.getId(), uid, "BUSINESS_CREATED", "business", b.getId(), b.getName());

        User u = users.findById(uid).orElseThrow(() -> ApiException.unauthorized("Sign in to continue"));
        return auth.issue(u, m, UUID.randomUUID());
    }

    @Transactional(readOnly = true)
    public BusinessDto current() {
        return BusinessDto.of(businesses.findById(Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Business not found")));
    }

    @Transactional
    public BusinessDto update(UpdateRequest r) {
        UUID bid = Tenant.businessId();
        Business b = businesses.findById(bid).orElseThrow(() -> ApiException.notFound("Business not found"));
        b.setName(r.name().trim());
        b.setSubtype(r.subtype());
        b.setDescription(r.description());
        b.setPhone(r.phone());
        b.setEmail(r.email());
        b.setAddress(r.address());
        b.setTimezone(validZone(r.timezone()));
        b.setAllowNegativeStock(r.allowNegativeStock());
        audit.record(bid, Tenant.userId(), "BUSINESS_UPDATED", "business", bid, "Profile or stock settings changed");
        return BusinessDto.of(b);
    }
}
