package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Single Bukkit listener that routes inventory events to whichever EditorGui owns the open
 * top inventory. Registered lazily once (mirrors RecipeViewGui's dispatcher pattern).
 */
public final class RecipeEditorListener implements Listener {

    private static volatile boolean registered = false;

    private RecipeEditorListener() {
    }

    public static void ensureRegistered(FarmersDelightPlugin plugin) {
        if (registered) {
            return;
        }
        synchronized (RecipeEditorListener.class) {
            if (registered) {
                return;
            }
            Bukkit.getPluginManager().registerEvents(new RecipeEditorListener(), plugin);
            registered = true;
        }
    }

    /** Reset so a soft re-enable re-registers a fresh listener. */
    public static void reset() {
        registered = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorGui gui) {
            gui.handleClick(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorGui gui) {
            gui.handleDrag(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorGui gui) {
            gui.handleClose(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof EditorGui) {
            event.getPlayer().setItemOnCursor(null);
        }
    }
}
