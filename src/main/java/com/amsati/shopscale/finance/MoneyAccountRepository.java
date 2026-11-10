package com.amsati.shopscale.finance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MoneyAccountRepository extends JpaRepository<MoneyAccount, UUID> {
    Optional<MoneyAccount> findByIdAndBusinessId(UUID id, UUID businessId);
    List<MoneyAccount> findByBusinessIdOrderByCreatedAtAsc(UUID businessId);
    Optional<MoneyAccount> findFirstByBusinessIdAndKindAndActiveTrueOrderByCreatedAtAsc(UUID businessId, String kind);
    boolean existsByBusinessIdAndNameIgnoreCase(UUID businessId, String name);
}
