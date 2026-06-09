package com.huidu.farmersdelight.block.behavior;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Legacy stove helper. The instance-based state model and tick loop that used to live here have
 * been removed: runtime stove logic is owned entirely by
 * com.huidu.farmersdelight.manager.StoveManager (which keeps its own
 * com.huidu.farmersdelight.util.CampfireRecipeCache). Only the static campfire-recipe
 * lookup cache survives, since reload/disable paths still reference clearRecipeCache().
 */
@Deprecated(forRemoval = false)
public final class StoveCookingBlockEntity {

    private static final int MAX_CACHE_SIZE = 100;
    private static final LinkedHashMap<Material, CookingRecipe<?>> recipeCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Material, CookingRecipe<?>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private StoveCookingBlockEntity() {
    }

    public static CookingRecipe<?> findCampfireRecipe(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;

        Material material = item.getType();

        synchronized (recipeCache) {
            CookingRecipe<?> cached = recipeCache.get(material);
            if (cached != null) {
                return cached;
            }
        }

        Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();
        while (recipeIterator.hasNext()) {
            Recipe recipe = recipeIterator.next();
            if (recipe instanceof CookingRecipe<?> cookingRecipe) {
                var ingredient = cookingRecipe.getInputChoice();
                if (ingredient != null && ingredient.test(item)) {
                    synchronized (recipeCache) {
                        recipeCache.put(material, cookingRecipe);
                    }
                    return cookingRecipe;
                }
            }
        }

        return null;
    }

    public static void clearRecipeCache() {
        synchronized (recipeCache) {
            recipeCache.clear();
        }
    }
}
