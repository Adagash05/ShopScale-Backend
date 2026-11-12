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
@Table(name = "store_enquiries")
@Getter @Setter @NoArgsConstructor
public class StoreEnquiry {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private String name;
    private String phone;
    private String email;
    private LocalDate eventDate;
    private Integer guests;
    private String message;
    private String status = "NEW";
    private Instant createdAt = Instant.now();
}
