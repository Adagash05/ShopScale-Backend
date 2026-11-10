package com.amsati.shopscale.recipe;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecipeLineRepository extends JpaRepository<RecipeLine, UUID> {
    List<RecipeLine> findByRecipeId(UUID recipeId);
    List<RecipeLine> findByRecipeIdIn(Collection<UUID> recipeIds);

    @Modifying
    @Query("delete from RecipeLine l where l.recipeId = :r")
    void deleteByRecipe(@Param("r") UUID recipeId);
}
