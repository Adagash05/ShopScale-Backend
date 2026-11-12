package com.amsati.shopscale.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revokes the whole sign-in (logout, or a spent token was replayed). Clears rotated_at so nothing in it can be retried. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = coalesce(t.revokedAt, :now), t.rotatedAt = null where t.familyId = :family")
    int revokeFamily(@Param("family") UUID family, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = coalesce(t.revokedAt, :now), t.rotatedAt = null where t.userId = :u")
    int revokeAllForUser(@Param("u") UUID userId, @Param("now") Instant now);

    /** Retiring the still-active tokens of a sign-in after a benign retry, so only the newest one stays usable. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now, t.rotatedAt = :now where t.familyId = :family and t.revokedAt is null")
    int rotateActive(@Param("family") UUID family, @Param("now") Instant now);

    /** One active token per sign-in (device). */
    List<RefreshToken> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = coalesce(t.revokedAt, :now), t.rotatedAt = null where t.familyId = :family and t.userId = :u")
    int revokeFamilyForUser(@Param("family") UUID family, @Param("u") UUID userId, @Param("now") Instant now);
}
