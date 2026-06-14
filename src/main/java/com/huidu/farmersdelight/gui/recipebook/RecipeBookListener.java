package com.huidu.farmersdelight.gui.recipebook;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Single dispatcher for the generic recipe book + editor. The book is read-only navigation (all clicks
 * cancelled); the editor uses a copy-based model — clicking a player item copies it to the cursor, and
 * placing into an item slot stores a 1-count template, so no real items are ever moved (no dupe/loss).
 */
public final class RecipeBookListener implements Listener {

    private static volatile boolean registered = false;

    public static void ensureRegistered() {
        if (registered) {
            return;
        }
        synchronized (RecipeBookListener.class) {
            if (registered) {
                return;
            }
            FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
            if (plugin == null) {
                return;
            }
            Bukkit.getPluginManager().registerEvents(new RecipeBookListener(), plugin);
            registered = true;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (holder instanceof RecipeBookGui book) {
            event.setCancelled(true);
            if (event.getClickedInventory() == event.getInventory()) {
                book.handleClick(player, event.getRawSlot());
            }
            return;
        }
        if (holder instanceof RecipeEditorView editor) {
            event.setCancelled(true);
            int raw = event.getRawSlot();
            boolean top = event.getClickedInventory() == event.getInventory();
            if (top) {
                if (editor.isEditableSlot(raw)) {
                    ItemStack cursor = event.getCursor();
                    if (cursor == null || cursor.getType().isAir()) {
                        event.getInventory().setItem(raw, null);
                    } else {
                        ItemStack template = cursor.clone();
                        template.setAmount(1);
                        event.getInventory().setItem(raw, template);
                    }
                } else {
                    editor.handleButton(player, raw, event.isRightClick());
                }
            } else {
                // Clicking the player's own inventory: copy item to cursor, or discard a held template.
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir()) {
                    player.setItemOnCursor(null);
                } else {
                    ItemStack current = event.getCurrentItem();
                    if (current != null && !current.getType().isAir()) {
                        ItemStack copy = current.clone();
                        copy.setAmount(1);
                        player.setItemOnCursor(copy);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof RecipeBookGui || holder instanceof RecipeEditorView) {
            Inventory top = event.getInventory();
            for (int raw : event.getRawSlots()) {
                if (raw < top.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof RecipeEditorView
                && event.getPlayer() instanceof Player player) {
            // Discard any template copy left on the cursor (it was never a real item).
            player.setItemOnCursor(null);
        }
    }
}
