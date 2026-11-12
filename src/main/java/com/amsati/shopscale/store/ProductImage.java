package com.amsati.shopscale.store;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "product_images")
@Getter @Setter @NoArgsConstructor
public class ProductImage {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private UUID productId;
    private String contentType;
    private int sizeBytes;
    private boolean gallery;
    private int position;
    @Basic(fetch = FetchType.LAZY)
    private byte[] data;
    private Instant createdAt = Instant.now();
}
