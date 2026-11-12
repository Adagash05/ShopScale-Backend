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
@Table(name = "store_order_item_options")
@Getter @Setter @NoArgsConstructor
public class StoreOrderItemOption {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID itemId;
    private UUID modifierId;
    private String name;
    private BigDecimal priceDelta = BigDecimal.ZERO;
}
