package com.amsati.shopscale.restaurant;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KitchenItemModifierRepository extends JpaRepository<KitchenItemModifier, UUID> {
    List<KitchenItemModifier> findByItemIdIn(Collection<UUID> itemIds);
}
