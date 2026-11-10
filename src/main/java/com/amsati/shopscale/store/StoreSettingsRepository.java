package com.amsati.shopscale.store;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface StoreSettingsRepository extends JpaRepository<StoreSettings, UUID> {
    Optional<StoreSettings> findByCustomDomainIgnoreCase(String domain);
}
