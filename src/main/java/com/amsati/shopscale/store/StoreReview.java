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

@Entity
@Table(name = "store_reviews")
@Getter @Setter @NoArgsConstructor
public class StoreReview {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID productId;
    private UUID orderId;
    private String authorName;
    private int rating;
    private String body;
    private String status = "PENDING";
    private Instant createdAt = Instant.now();
}
