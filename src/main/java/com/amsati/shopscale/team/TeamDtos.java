package com.amsati.shopscale.team;

import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TeamDtos {
    private TeamDtos() {}

    public record RoleRef(@NotNull Role role, UUID roleId) {}

    public record InviteRequest(@NotBlank @Size(max = 150) String fullName, @NotBlank @Email @Size(max = 255) String email,
                                @Size(max = 40) String phone, @NotNull Role role, UUID roleId) {}

    public record RoleRequest(@NotBlank @Size(max = 80) String name, @Size(max = 300) String description,
                              @NotEmpty(message = "Choose at least one permission") Set<Permission> permissions) {}

    public record AcceptRequest(@NotBlank String token, @Size(max = 150) String fullName,
                                @NotBlank @Size(min = 8, max = 72, message = "Use 8 to 72 characters") String password) {}

    public record MemberDto(UUID membershipId, UUID userId, String fullName, String email, String phone, Role role,
                            UUID roleId, String roleName, boolean active, boolean owner, Instant joinedAt, Instant lastActiveAt) {}

    public record InvitationDto(UUID id, String fullName, String email, String phone, Role role, UUID roleId, String roleName,
                                String status, Instant expiresAt, Instant createdAt, boolean emailSent) {}

    public record InviteResult(InvitationDto invitation, String inviteLink, boolean emailSent) {}

    public record InvitePreview(String businessName, String fullName, String email, String roleName, boolean accountExists) {}

    public record RoleDto(String key, UUID id, Role role, String name, String description, boolean system,
                          List<String> permissions, long memberCount) {}

    public record PermissionItem(String key, String label) {}
    public record PermissionGroup(String module, List<PermissionItem> permissions) {}
}
