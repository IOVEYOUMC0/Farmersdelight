package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.recipe.RecipeAutoFillPlan;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class CookingPotRecipeTransfer {
    private CookingPotRecipeTransfer() { }

    public static RecipeAutoFillPlan.Result fill(FarmersDelightPlugin plugin, Player player,
                                                 CookingPotBlockEntity pot, Location location, List<String> ingredients) {
        return fill(plugin, player, pot, location, ingredients, String::equals);
    }

    public static RecipeAutoFillPlan.Result fill(FarmersDelightPlugin plugin, Player player,
                                                 CookingPotBlockEntity pot, Location location, List<String> ingredients,
                                                 java.util.function.BiPredicate<String, String> matches) {
        if (plugin.scheduler().isFolia() && (!Bukkit.isOwnedByCurrentRegion(player)
                || !plugin.scheduler().isOwnedByCurrentRegion(location))) return RecipeAutoFillPlan.Result.WRONG_REGION;
        synchronized (pot.getLock()) {
            int[] slots = pot.getLayout().inputSlots();
            ItemStack[] beforeSource = RecipeAutoFillPlan.copy(player.getInventory().getStorageContents());
            ItemStack[] beforeTarget = new ItemStack[slots.length];
            ItemStack[] inventory = pot.getInventoryInternal();
            for (int i = 0; i < slots.length; i++) beforeTarget[i] = inventory[slots[i]];
            beforeTarget = RecipeAutoFillPlan.copy(beforeTarget);
            RecipeAutoFillPlan.Plan plan = RecipeAutoFillPlan.prepare(ingredients, beforeSource, beforeTarget,
                    player.getGameMode() == GameMode.CREATIVE, ItemUtils::resolveItemId, matches);
            if (plan.result() != RecipeAutoFillPlan.Result.FILLED) return plan.result();
            try {
                player.getInventory().setStorageContents(plan.playerItems());
                for (int i = 0; i < slots.length; i++) pot.setSlot(slots[i], plan.potItems()[i]);
            } catch (RuntimeException error) {
                for (int i = 0; i < slots.length; i++) pot.setSlot(slots[i], beforeTarget[i]);
                try { player.getInventory().setStorageContents(beforeSource); }
                catch (RuntimeException rollback) { error.addSuppressed(rollback); }
                pot.syncWorldlyContainer();
                throw error;
            }
        }
        // Publish a single dirty/wake notification after the complete batch is visible.
        pot.syncWorldlyContainer();
        return RecipeAutoFillPlan.Result.FILLED;
    }
}
