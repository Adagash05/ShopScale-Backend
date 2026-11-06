package com.amsati.shopscale.team;

import com.amsati.shopscale.auth.AuthResponse;
import com.amsati.shopscale.team.TeamDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/team")
@RequiredArgsConstructor
public class TeamController {
    private final TeamService team;
    private final InvitationService invitations;

    // ---- public: the invited person is not signed in yet ----
    @GetMapping("/invitations/preview")
    public InvitePreview preview(@RequestParam String token) { return invitations.preview(token); }

    @PostMapping("/invitations/accept")
    public AuthResponse accept(@Valid @RequestBody AcceptRequest r) { return invitations.accept(r); }

    // ---- members ----
    @GetMapping("/members")
    @PreAuthorize("@access.has('TEAM_VIEW')")
    public List<MemberDto> members() { return team.members(); }

    @PutMapping("/members/{id}/role")
    @PreAuthorize("@access.has('TEAM_ASSIGN_ROLES')")
    public MemberDto changeRole(@PathVariable UUID id, @Valid @RequestBody RoleRef ref) { return team.changeRole(id, ref); }

    @PostMapping("/members/{id}/deactivate")
    @PreAuthorize("@access.has('TEAM_DEACTIVATE')")
    public MemberDto deactivate(@PathVariable UUID id) { return team.setActive(id, false); }

    @PostMapping("/members/{id}/reactivate")
    @PreAuthorize("@access.has('TEAM_DEACTIVATE')")
    public MemberDto reactivate(@PathVariable UUID id) { return team.setActive(id, true); }

    // ---- invitations ----
    @GetMapping("/invitations")
    @PreAuthorize("@access.has('TEAM_VIEW')")
    public List<InvitationDto> listInvitations() { return team.listInvitations(); }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('TEAM_INVITE')")
    public InviteResult invite(@Valid @RequestBody InviteRequest r) { return team.invite(r); }

    @PostMapping("/invitations/{id}/resend")
    @PreAuthorize("@access.has('TEAM_INVITE')")
    public InviteResult resend(@PathVariable UUID id) { return team.resend(id); }

    @PostMapping("/invitations/{id}/revoke")
    @PreAuthorize("@access.has('TEAM_INVITE')")
    public InvitationDto revoke(@PathVariable UUID id) { return team.revoke(id); }

    // ---- roles ----
    @GetMapping("/roles")
    @PreAuthorize("@access.has('TEAM_VIEW')")
    public List<RoleDto> roles() { return team.roles(); }

    @GetMapping("/permissions")
    @PreAuthorize("@access.has('TEAM_VIEW')")
    public List<PermissionGroup> permissions() { return team.catalogue(); }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('ROLES_CREATE')")
    public RoleDto createRole(@Valid @RequestBody RoleRequest r) { return team.createRole(r); }

    @PutMapping("/roles/{id}")
    @PreAuthorize("@access.has('PERMISSIONS_MANAGE')")
    public RoleDto updateRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest r) { return team.updateRole(id, r); }

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has('PERMISSIONS_MANAGE')")
    public void deleteRole(@PathVariable UUID id) { team.deleteRole(id); }
}
