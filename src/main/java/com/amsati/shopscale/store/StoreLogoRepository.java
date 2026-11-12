package com.amsati.shopscale.store;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreLogoRepository extends JpaRepository<StoreLogo, UUID> {}
