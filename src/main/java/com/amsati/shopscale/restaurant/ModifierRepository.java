package com.amsati.shopscale.restaurant;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModifierRepository extends JpaRepository<Modifier, UUID> {
    List<Modifier> findByGroupIdIn(Collection<UUID> groupIds);
    List<Modifier> findByGroupId(UUID groupId);
    Optional<Modifier> findByIdAndBusinessId(UUID id, UUID businessId);
}
