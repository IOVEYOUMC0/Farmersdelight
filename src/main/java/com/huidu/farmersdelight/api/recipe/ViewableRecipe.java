package com.huidu.farmersdelight.api.recipe;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * A type-agnostic, displayable recipe shown in the generic recipe book. Addons adapt their own recipes
 * to this so FarmersDelight's recipe book can render them without knowing the addon's internal types.
 *
 * Lives in the name-stable {@code api} package; uses only Bukkit / Adventure / java types.
 */
public interface ViewableRecipe {

    /** Unique id within its {@link RecipeType} (used for detail/editor lookup). */
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

    /**
     * Extra display items keyed by a custom role name, for a type that supplies its own detail layout
     * (see {@link RecipeType#detailLayout()}). The detail view places each role's items into the layout
     * slots whose legend maps to that role — e.g. {@code {"fluid": [...], "return": [...]}} for the keg.
     * Roles {@code ingredient}/{@code result} are handled by {@link #inputs()}/{@link #result()} and need
     * not be repeated here. Defaults to none.
     */
    default Map<String, List<ItemStack>> displaySlots() {
        return Map.of();
    }

    /**
     * Whether {@code player} can currently make this recipe (has the required inputs). Used by the recipe
     * book's optional "craftable only" filter. Defaults to true (always shown) for types that don't
     * implement an inventory check.
     */
    default boolean craftableBy(Player player) {
        return true;
    }
}
