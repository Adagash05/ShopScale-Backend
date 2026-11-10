package com.amsati.shopscale.branch;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Stock of one variant at one branch. Invariant: the sum over branches equals product_variants.quantity. */
@Entity
@Table(name = "branch_stock")
@Getter @Setter @NoArgsConstructor
public class BranchStock {
    @Embeddable
    @Getter @Setter @NoArgsConstructor
    public static class Key implements Serializable {
        private UUID branchId;
        private UUID variantId;
        public Key(UUID branchId, UUID variantId) { this.branchId = branchId; this.variantId = variantId; }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && java.util.Objects.equals(branchId, k.branchId) && java.util.Objects.equals(variantId, k.variantId);
        }
        @Override public int hashCode() { return java.util.Objects.hash(branchId, variantId); }
    }

    @EmbeddedId
    private Key id;
    private UUID businessId;
    private BigDecimal quantity = BigDecimal.ZERO;
    private BigDecimal reserved = BigDecimal.ZERO;
    private Instant updatedAt = Instant.now();

    public BranchStock(UUID branchId, UUID variantId, UUID businessId) {
        this.id = new Key(branchId, variantId);
        this.businessId = businessId;
    }
}
