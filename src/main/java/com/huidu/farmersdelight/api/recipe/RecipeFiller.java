package com.huidu.farmersdelight.api.recipe;

import org.bukkit.entity.Player;

/**
 * Supplied when a recipe book is opened from a station GUI (e.g. a keg). It moves a recipe's ingredients
 * from the player's inventory into that station, like the cooking pot's "fill" button. The recipe book
 * only shows a Fill button when a filler is present; the standalone book has none.
 *
 * <p>Implementations must move items dupe-safely (remove from the player exactly what is placed).
 */
public interface RecipeFiller {

    /**
     * Fills the station this filler targets with recipe's ingredients from player's
     * inventory. Returns true if anything was filled (the filler may reopen its station GUI).
     */
    boolean fill(Player player, ViewableRecipe recipe);
}
