package com.amsati.shopscale.recipe;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "recipe_lines")
@Getter @Setter @NoArgsConstructor
public class RecipeLine {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID recipeId;
    private UUID ingredientVariantId;
    private BigDecimal quantity;
}
