package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only queries into FarmersDelight's cooking pot and cutting board recipes. Returns only Bukkit
 * types (ItemStack) — internal recipe records never cross the API boundary. Lives in the
 * name-stable api package.
 */
public final class FarmersDelightRecipes {

    private FarmersDelightRecipes() {
    }

    /** True if the given inputs + container match a cooking pot recipe. */
    public static boolean matchesCookingPot(List<ItemStack> inputs, ItemStack container) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || inputs == null) {
            return false;
        }
        return plugin.getCookingPotRecipes().matchRecipe(inputs, container) != null;
    }

    /** The result of the cooking pot recipe matching the given inputs + container, or null. */
    public static ItemStack cookingPotResult(List<ItemStack> inputs, ItemStack container) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || inputs == null) {
            return null;
        }
        CookingPotRecipe recipe = plugin.getCookingPotRecipes().matchRecipe(inputs, container);
        return recipe == null ? null : recipe.getResult();
    }

    /** True if any cutting board recipe accepts input. */
    public static boolean hasCuttingBoardRecipe(ItemStack input) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && plugin.getCuttingBoardRecipes().hasAnyRecipeFor(input);
    }

    /** The result items of the cutting board recipe matching input + tool (empty if none). */
    public static List<ItemStack> cuttingBoardResults(ItemStack input, ItemStack tool) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) {
            return List.of();
        }
        CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().matchRecipe(input, tool);
        if (recipe == null) {
            return List.of();
        }
        List<ItemStack> results = new ArrayList<>();
        for (CuttingBoardRecipe.ResultEntry entry : recipe.getResults()) {
            if (entry.getItem() != null) {
                results.add(entry.getItem().clone());
            }
        }
        return results;
    }
}
