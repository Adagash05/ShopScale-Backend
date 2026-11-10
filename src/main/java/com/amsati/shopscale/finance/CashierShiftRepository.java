package com.amsati.shopscale.finance;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashierShiftRepository extends JpaRepository<CashierShift, UUID> {
    Optional<CashierShift> findByBusinessIdAndUserIdAndStatus(UUID businessId, UUID userId, String status);
    Optional<CashierShift> findByIdAndBusinessId(UUID id, UUID businessId);
    Page<CashierShift> findByBusinessIdOrderByOpenedAtDesc(UUID businessId, Pageable pageable);
    Page<CashierShift> findByBusinessIdAndUserIdOrderByOpenedAtDesc(UUID businessId, UUID userId, Pageable pageable);
}
