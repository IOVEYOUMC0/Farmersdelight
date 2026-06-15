package com.huidu.farmersdelight.api;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import com.huidu.farmersdelight.api.recipe.RecipeFiller;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.scheduler.ApiTask;
import com.huidu.farmersdelight.gui.recipebook.RecipeBookGui;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stable, addon-facing entry point for FarmersDelight services (scheduling + experience). Lives in the
 * name-stable {@code api} package; every signature uses only Bukkit / java / other {@code api} types so
 * addons keep working against the obfuscated jar. Method bodies delegate to renamed internals freely.
 */
public final class FarmersDelightApi {

    private static final FarmersDelightApi INSTANCE = new FarmersDelightApi();

    private final Map<String, RecipeType> recipeTypes = Collections.synchronizedMap(new LinkedHashMap<>());

    private FarmersDelightApi() {
    }

    public static FarmersDelightApi get() {
        return INSTANCE;
    }

    /** Registers an addon recipe type so it appears in the generic recipe book (and editor, if provided). */
    public void registerRecipeType(RecipeType type) {
        if (type != null && type.id() != null) {
            recipeTypes.put(type.id(), type);
        }
    }

    /** Removes a previously registered recipe type (e.g. on addon disable). */
    public void unregisterRecipeType(String typeId) {
        if (typeId != null) {
            recipeTypes.remove(typeId);
        }
    }

    /**
     * Registers (or replaces) a FarmersDelight cooking-pot recipe at runtime, so addon dishes are cooked
     * by the real cooking pot. Ingredient specs use the recipe-file syntax ("ns:id", "#ns:tag", "a|b").
     * {@code container} is the required bowl/bottle (null = none); {@code result} carries its own amount.
     * The recipe persists across {@code /fd reload}. No-op when FarmersDelight is unavailable.
     */
    public void registerCookingPotRecipe(String id, List<String> ingredients, ItemStack container,
                                         ItemStack result, double experience, int cookTime, String category) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !isAvailable() || id == null || ingredients == null || result == null) {
            return;
        }
        plugin.getCookingPotRecipes().registerExternalRecipe(id, ingredients,
                container == null ? null : container.clone(), result.clone(),
                (float) experience, cookTime, category);
    }

    /** Removes a cooking-pot recipe registered via {@link #registerCookingPotRecipe}. */
    public void unregisterCookingPotRecipe(String id) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && isAvailable() && id != null) {
            plugin.getCookingPotRecipes().unregisterExternalRecipe(id);
        }
    }

    /**
     * Registers (or replaces) a FarmersDelight cutting-board recipe at runtime, so addon items can be cut
     * on the real cutting board. {@code input}/{@code tool} use the recipe-file syntax ("ns:id", "#ns:tag");
     * each {@code results} stack carries its own amount; {@code sound} is a sound id (null = default knife).
     * The recipe persists across {@code /fd reload}. No-op when FarmersDelight is unavailable.
     */
    public void registerCuttingBoardRecipe(String id, String input, String tool,
                                           List<ItemStack> results, String sound) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !isAvailable() || id == null || input == null || tool == null || results == null) {
            return;
        }
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack result : results) {
            if (result != null) {
                copies.add(result.clone());
            }
        }
        plugin.getCuttingBoardRecipes().registerExternalRecipe(id, input, tool, copies, sound);
    }

    /** Removes a cutting-board recipe registered via {@link #registerCuttingBoardRecipe}. */
    public void unregisterCuttingBoardRecipe(String id) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && isAvailable() && id != null) {
            plugin.getCuttingBoardRecipes().unregisterExternalRecipe(id);
        }
    }

    /** All registered recipe types, in registration order. */
    public List<RecipeType> recipeTypes() {
        synchronized (recipeTypes) {
            return new ArrayList<>(recipeTypes.values());
        }
    }

    public RecipeType recipeType(String typeId) {
        return typeId == null ? null : recipeTypes.get(typeId);
    }

    /** Opens the standalone recipe book (no Fill button) for {@code player}. */
    public void openRecipeBook(Player player) {
        openRecipeBook(player, null);
    }

    /**
     * Opens the recipe book for {@code player} with a {@link RecipeFiller}. When non-null, recipe detail
     * shows a Fill button that fills the filler's station; pass null for a read-only standalone book.
     */
    public void openRecipeBook(Player player, RecipeFiller filler) {
        if (player != null) {
            RecipeBookGui.openMenu(player, filler);
        }
    }

    /** Opens the generic recipe editor for {@code recipeId} of a registered type (null id = new recipe). */
    public void openRecipeEditor(Player player, String typeId, String recipeId) {
        RecipeType type = recipeType(typeId);
        if (player != null && type != null && type.editor() != null) {
            RecipeBookGui.openEditor(player, type, recipeId);
        }
    }

    /** True when FarmersDelight is present and enabled; addons should guard calls with this. */
    public boolean isAvailable() {
        return FarmersDelightPlugin.getInstance() != null && FarmersDelightPlugin.isEnabled0();
    }

    public boolean isFolia() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && plugin.scheduler().isFolia();
    }

    /** True if {@code block} is a configured heat source (cooking-pot heating). */
    public boolean isHeatSource(Block block) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && block != null && plugin.getHeatSourceConfig().isHeatSource(block);
    }

    /** True if {@code block} is a configured heat conductor (passes heat from a source below it). */
    public boolean isConductor(Block block) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && block != null && plugin.getHeatSourceConfig().isConductor(block);
    }

    /** Runs {@code task} on the region owning {@code location} (Folia-safe; immediate on Paper). */
    public void runAtLocation(Location location, Runnable task) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || task == null) {
            return;
        }
        plugin.scheduler().runAt(location, task);
    }

    /** Runs {@code task} {@code delayTicks} later on the region owning {@code location} (Folia-safe). */
    public void runLaterAtLocation(Location location, Runnable task, long delayTicks) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || task == null) {
            return;
        }
        plugin.scheduler().runLaterAt(location, task, delayTicks);
    }

    /** Schedules a repeating task; returns a handle to cancel it. Never null (NOOP when FD absent). */
    public ApiTask runRepeating(Runnable task, long delayTicks, long periodTicks) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || task == null) {
            return ApiTask.NOOP;
        }
        PluginTask pluginTask = plugin.scheduler().runRepeating(task, delayTicks, periodTicks);
        return new ApiTask() {
            @Override
            public void cancel() {
                pluginTask.cancel();
            }

            @Override
            public boolean isCancelled() {
                return pluginTask.isCancelled();
            }
        };
    }

    /**
     * Awards crafting experience to {@code player} for a produced {@code result}, mirroring the cooking
     * pot: optional vanilla XP orbs dropped at {@code location} (gated by the cooking-pot XP config),
     * AuraSkills XP, and a {@link ProfessionCookingExperienceEvent} carrying {@code source}. Folia-safe.
     */
    public void awardCraftingExperience(Player player, Location location, ItemStack result,
                                        double baseExperience, String source) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || player == null) {
            return;
        }
        ItemStack resultCopy = result == null ? null : result.clone();
        plugin.scheduler().runAt(location, () -> {
            if (baseExperience > 0.0D && plugin.shouldDropCookingPotVanillaExperience()
                    && location != null && location.getWorld() != null) {
                dropExperienceOrbs(location.getWorld(), location, baseExperience);
            }
            plugin.awardCookingPotAuraSkillsExperience(player, baseExperience);
            Bukkit.getPluginManager().callEvent(new ProfessionCookingExperienceEvent(
                    player.getUniqueId(), player.getName(), source, resultCopy, (float) baseExperience));
        });
    }

    private static void dropExperienceOrbs(World world, Location location, double totalExp) {
        // Probabilistic rounding so sub-1.0 exp isn't floored away; expected total holds over many takes.
        int expValue = (int) Math.floor(totalExp);
        double fraction = totalExp - expValue;
        if (fraction > 0.0D && ThreadLocalRandom.current().nextDouble() < fraction) {
            expValue += 1;
        }
        if (expValue > 0) {
            int amount = expValue;
            world.spawn(location, ExperienceOrb.class, orb -> orb.setExperience(amount));
        }
    }
}
