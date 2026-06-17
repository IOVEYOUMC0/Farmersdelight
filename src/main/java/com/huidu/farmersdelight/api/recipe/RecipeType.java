package com.huidu.farmersdelight.api.recipe;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A registrable recipe type contributed by an addon (e.g. the keg's fermenting recipes). Registered via
 * {@code FarmersDelightApi.registerRecipeType}, it appears as a category in the generic recipe book and,
 * if it provides an {@link #editor()}, is editable through the generic editor.
 *
 * <p>Lives in the name-stable {@code api} package; uses only Bukkit / Adventure / api types.
 */
public interface RecipeType {

    /** Unique category id, e.g. {@code "brewinandchewin:keg"}. */
    String id();

    /** Category title shown in the book's main menu and headers. */
    Component title();

    /** Icon for the category button in the main menu. */
    ItemStack icon();

    /** Current snapshot of this type's recipes. */
    List<ViewableRecipe> recipes();

    /** Finds one recipe by id; defaults to scanning {@link #recipes()}. */
    default ViewableRecipe recipe(String id) {
        if (id == null) {
            return null;
        }
        for (ViewableRecipe recipe : recipes()) {
            if (id.equals(recipe.id())) {
                return recipe;
            }
        }
        return null;
    }

    /** Editor for this type, or null if recipes aren't editable in-game. */
    default RecipeEditor editor() {
        return null;
    }

    /**
     * This type's own recipe-LIST page layout. When non-null, FarmersDelight renders this type as an
     * independent book (its own title/grid/decorations) instead of the shared {@code recipe-book-gui}
     * config — so it never piles into a shared menu with other addons. Null = use the shared book.
     */
    default RecipeBookLayout listLayout() {
        return null;
    }

    /** This type's own recipe-DETAIL page layout (see {@link #listLayout()}). Null = use the shared book.
     * A custom detail layout can place {@link ViewableRecipe#displaySlots()} roles (fluid, return, …). */
    default RecipeBookLayout detailLayout() {
        return null;
    }
}
