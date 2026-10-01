package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

/** Inventory transitions run after the click transaction, on the viewer's owning thread. */
final class EditorNavigation {
    private EditorNavigation() { }

    static boolean allowed(FarmersDelightPlugin plugin, Player player) {
        return plugin.isEnabled() && player.isOnline() && player.hasPermission("farmersdelight.admin")
                && plugin.getConfigBoolean(true, "recipe-editor.enabled");
    }

    static void next(FarmersDelightPlugin plugin, Player player, Inventory expected, Runnable action) {
        if (!allowed(plugin, player)) return;
        plugin.scheduler().runLaterForEntity(player, () -> {
            if (allowed(plugin, player) && player.getOpenInventory().getTopInventory() == expected) action.run();
        }, 1L);
    }

    static void afterPlayerClose(FarmersDelightPlugin plugin, Player player, InventoryCloseEvent event, Runnable back) {
        if (back == null || !allowed(plugin, player) || event.getReason() != InventoryCloseEvent.Reason.PLAYER) return;
        plugin.scheduler().runLaterForEntity(player, () -> {
            // A delayed return must not replace another screen the player opened in the meantime.
            if (allowed(plugin, player) && player.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING) back.run();
        }, 1L);
    }
}
