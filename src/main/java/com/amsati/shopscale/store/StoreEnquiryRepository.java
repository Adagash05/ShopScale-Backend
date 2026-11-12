package com.amsati.shopscale.store;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreEnquiryRepository extends JpaRepository<StoreEnquiry, UUID> {
    List<StoreEnquiry> findTop200ByBusinessIdOrderByCreatedAtDesc(UUID businessId);
    Optional<StoreEnquiry> findByIdAndBusinessId(UUID id, UUID businessId);
    long countByBusinessIdAndStatus(UUID businessId, String status);
}
