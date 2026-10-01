package com.huidu.farmersdelight.gui.recipebook;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.StaticItem;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/** Owns virtual read-only windows; every visible slot contains display items rather than storage links. */
public final class ReadOnlyRecipeWindows {
    private final FarmersDelightPlugin plugin;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private volatile boolean initialized;
    private volatile boolean unavailable;

    public ReadOnlyRecipeWindows(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized boolean initialize() {
        if (unavailable) {
            return false;
        }
        if (!initialized) {
            try {
                SparrowUI.getInstance().setUp(plugin);
                SparrowUI.getInstance().addDisableHandler(windows::clear);
                initialized = true;
            } catch (RuntimeException | LinkageError error) {
                unavailable = true;
                plugin.getLogger().log(Level.WARNING, "Sparrow UI unavailable; recipe books use Bukkit inventories", error);
                return false;
            }
        }
        return true;
    }

    public boolean open(Player player, Inventory display, Component title, BiConsumer<Integer, Boolean> click,
                        Runnable closed) {
        if (!initialized || unavailable) {
            return false;
        }
        try {
            return openWindow(player, display, title, click, closed);
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().log(Level.WARNING, "Could not build Sparrow recipe book; using Bukkit inventory", error);
            return false;
        }
    }

    private boolean openWindow(Player player, Inventory display, Component title, BiConsumer<Integer, Boolean> click,
                               Runnable onClosed) {
        Inventory previousInventory = player.getOpenInventory().getTopInventory();
        Pane upper = Pane.empty(9, display.getSize() / 9);
        UUID playerId = player.getUniqueId();
        for (int slot = 0; slot < display.getSize(); slot++) {
            ItemStack icon = display.getItem(slot);
            if (icon == null || icon.getType().isAir()) {
                continue;
            }
            int clickedSlot = slot;
            upper.setItem(slot, new StaticItem(ItemProvider.constant(icon), (item, event) -> {
                if (windows.get(playerId) == event.window() && event.window().isOpen()) {
                    click.accept(clickedSlot, event.clickType().isShiftClick());
                }
            }));
        }
        // Display the player's inventory without linking fabricated menu items to real storage.
        // Navigation refreshes this snapshot; all lower-slot interactions remain read-only.
        Pane lower = Pane.empty(9, 4);
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem((slot + 9) % 36);
            if (item != null && !item.getType().isAir()) {
                lower.setItem(slot, Item.simple(item));
            }
        }
        Window window = Window.builder(upper).setLowerPane(lower).setTitle(title)
                .addOutsideClickHandler(event -> event.setCancelled(true))
                .addCloseHandler((closed, reason) -> {
                    if (windows.remove(playerId, closed)) {
                        onClosed.run();
                    }
                }).build(player);
        windows.put(playerId, window);
        try {
            window.open().whenComplete((result, error) -> {
                if (error != null || result == Window.OpenResult.VIEWER_UNAVAILABLE) {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not open Sparrow recipe book", error);
                        if (plugin.isEnabled()) {
                            try {
                                plugin.scheduler().runForEntity(player, () -> {
                                    if (windows.remove(playerId, window)) {
                                        if (player.isOnline() && player.getOpenInventory().getTopInventory() == previousInventory) {
                                            player.openInventory(display);
                                        } else {
                                            onClosed.run();
                                        }
                                    }
                                }, () -> {
                                    if (windows.remove(playerId, window)) onClosed.run();
                                });
                            } catch (RuntimeException stopped) {
                                if (windows.remove(playerId, window)) onClosed.run();
                            }
                        } else {
                            if (windows.remove(playerId, window)) onClosed.run();
                        }
                    } else {
                        if (windows.remove(playerId, window)) onClosed.run();
                    }
                }
            });
        } catch (RuntimeException | LinkageError error) {
            windows.remove(playerId, window);
            throw error;
        }
        return true;
    }

    public void closeAll() {
        if (!plugin.isEnabled()) {
            windows.clear();
            return; // Sparrow's disable handler owns structural window teardown.
        }
        windows.forEach((id, window) -> {
            // Drop identity before scheduling closure so delayed clicks cannot navigate during reload.
            if (windows.remove(id, window)) {
                window.close();
            }
        });
    }
}
