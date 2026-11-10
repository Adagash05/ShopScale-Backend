package com.amsati.shopscale.auth;

import com.amsati.shopscale.business.BusinessDto;
import com.amsati.shopscale.security.Role;
import java.util.List;
import java.util.UUID;

/** permissions are for the UI to show or hide things; the server re-checks every request. */
public record AuthResponse(String accessToken, String refreshToken, long expiresIn, UserDto user,
                           BusinessDto business, Role role, String roleName, List<String> permissions,
                           List<String> features, List<String> workflows, String planCode, String subscriptionStatus) {

    public record UserDto(UUID id, String email, String fullName, String phone) {}
}
