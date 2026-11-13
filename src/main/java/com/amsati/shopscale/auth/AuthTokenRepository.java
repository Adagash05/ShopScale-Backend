package com.amsati.shopscale.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {
    Optional<AuthToken> findByTokenHashAndKind(String tokenHash, String kind);

    /** Single-use guarantee: only one of several simultaneous requests carrying the same token gets 1 back. */
    @Modifying
    @Query("update AuthToken t set t.usedAt = :now where t.id = :id and t.usedAt is null and t.expiresAt > :now")
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    boolean existsByUserIdAndKindAndUsedAtIsNullAndCreatedAtAfter(UUID userId, String kind, Instant after);

    @Modifying
    @Query("update AuthToken t set t.usedAt = :now where t.userId = :u and t.kind = :k and t.usedAt is null")
    int invalidateOpen(@Param("u") UUID userId, @Param("k") String kind, @Param("now") Instant now);
}
