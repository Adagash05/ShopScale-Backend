package com.amsati.shopscale.restaurant;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "product_modifier_groups")
@IdClass(ProductModifierGroup.Key.class)
@Getter @Setter @NoArgsConstructor
public class ProductModifierGroup {
    @Id private UUID productId;
    @Id private UUID groupId;

    public ProductModifierGroup(UUID productId, UUID groupId) { this.productId = productId; this.groupId = groupId; }

    public record Key(UUID productId, UUID groupId) implements Serializable {}
}
