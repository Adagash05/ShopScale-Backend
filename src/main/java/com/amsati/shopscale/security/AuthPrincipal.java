package com.amsati.shopscale.security;

import java.util.UUID;

public record AuthPrincipal(UUID userId, String email, UUID businessId, Role role) {}
