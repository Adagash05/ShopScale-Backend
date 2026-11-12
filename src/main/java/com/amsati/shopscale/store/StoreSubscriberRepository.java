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

public interface StoreSubscriberRepository extends JpaRepository<StoreSubscriber, UUID> {
    boolean existsByBusinessIdAndEmailIgnoreCase(UUID businessId, String email);
    List<StoreSubscriber> findTop1000ByBusinessIdOrderByCreatedAtDesc(UUID businessId);
}
