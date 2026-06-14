package com.huidu.farmersdelight.api.recipe;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A type-agnostic, displayable recipe shown in the generic recipe book. Addons adapt their own recipes
 * to this so FarmersDelight's recipe book can render them without knowing the addon's internal types.
 *
 * <p>Lives in the name-stable api package; uses only Bukkit / Adventure / java types.
 */
public interface ViewableRecipe {

    /** Unique id within its RecipeType (used for detail/editor lookup). */
    String id();

    /** Input/ingredient items to display (already resolved to concrete stacks). */
    List<ItemStack> inputs();

    /** The produced item. */
    ItemStack result();

    /** Extra info lines shown in the detail view (e.g. time, experience). Defaults to none. */
    default List<Component> infoLines(Player viewer) {
        return List.of();
    }

    /** Icon shown in the recipe list; defaults to the result item. */
    default ItemStack icon() {
        return result();
    }
}
