package com.amsati.shopscale.subscription;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, String> {
    List<Plan> findByActiveTrueOrderBySortOrderAsc();
}
