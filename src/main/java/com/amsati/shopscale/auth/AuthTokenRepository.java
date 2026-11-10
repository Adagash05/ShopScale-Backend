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

    @Modifying
    @Query("update AuthToken t set t.usedAt = :now where t.userId = :u and t.kind = :k and t.usedAt is null")
    int invalidateOpen(@Param("u") UUID userId, @Param("k") String kind, @Param("now") Instant now);
}
