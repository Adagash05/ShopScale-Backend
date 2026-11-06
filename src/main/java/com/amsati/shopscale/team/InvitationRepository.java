package com.amsati.shopscale.team;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    Optional<Invitation> findByTokenHash(String tokenHash);
    Optional<Invitation> findByIdAndBusinessId(UUID id, UUID businessId);
    List<Invitation> findByBusinessIdOrderByCreatedAtDesc(UUID businessId);
    boolean existsByBusinessIdAndEmailIgnoreCaseAndStatus(UUID businessId, String email, String status);
    long countByBusinessIdAndStatus(UUID businessId, String status);
    long countByBusinessIdAndRoleId(UUID businessId, UUID roleId);
}
