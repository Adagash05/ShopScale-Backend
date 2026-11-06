package com.amsati.shopscale.business;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    Optional<Membership> findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(UUID userId);
    boolean existsByUserIdAndActiveTrue(UUID userId);
    Optional<Membership> findByUserIdAndBusinessIdAndActiveTrue(UUID userId, UUID businessId);
    Optional<Membership> findByUserIdAndBusinessId(UUID userId, UUID businessId);
    Optional<Membership> findByIdAndBusinessId(UUID id, UUID businessId);
    List<Membership> findByBusinessIdOrderByCreatedAtAsc(UUID businessId);
    List<Membership> findByUserIdAndActiveTrueOrderByCreatedAtAsc(UUID userId);
    long countByBusinessIdAndActiveTrue(UUID businessId);
    long countByBusinessIdAndActive(UUID businessId, boolean active);
    long countByBusinessIdAndRoleId(UUID businessId, UUID roleId);
}
