package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.PetFoodConfig;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HorseFeedTemptListener implements Listener {

    private static final long DEFAULT_TICK_INTERVAL = 10L;
    private static final int DEFAULT_TICK_BUDGET = 64;

    private final FarmersDelightPlugin plugin;
    private final Set<UUID> activeTempters = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PetFoodConfig.PetFoodDefinition> activeTemptDefinitions = new ConcurrentHashMap<>();
    private final Map<String, PetFoodConfig.PetFoodDefinition> temptFoods = new HashMap<>();
    private boolean enabled;
    private long tickInterval;
    private int tickBudget;
    private int tickCursor;
    private BukkitTask task;

    public HorseFeedTemptListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        reload();
    }

    public void reload() {
        loadConfig();
        restartTask();
    }

    private void restartTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        activeTempters.clear();
        activeTemptDefinitions.clear();
        if (!enabled) {
            return;
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickTemptGoals, tickInterval, tickInterval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        activeTempters.clear();
        activeTemptDefinitions.clear();
    }

    private void loadConfig() {
        PetFoodConfig config = plugin.getPetFoodConfig();
        long shortestInterval = Long.MAX_VALUE;
        temptFoods.clear();

        for (Map.Entry<String, PetFoodConfig.PetFoodDefinition> entry : config.getFoodDefinitions().entrySet()) {
            PetFoodConfig.PetFoodDefinition definition = entry.getValue();
            if (!definition.temptEnabled) {
                continue;
            }
            temptFoods.put(entry.getKey().toLowerCase(Locale.ROOT), definition);
            shortestInterval = Math.min(shortestInterval, definition.temptTickInterval);
        }

        enabled = !temptFoods.isEmpty();
        tickInterval = shortestInterval == Long.MAX_VALUE ? DEFAULT_TICK_INTERVAL : shortestInterval;
        tickBudget = Math.max(1, plugin.getConfig().getInt("performance.pet-tempt-tick-budget", DEFAULT_TICK_BUDGET));
        plugin.getLogger().info("Loaded pet-food tempt config: enabled=" + enabled
                + ", foods=" + temptFoods.size()
                + ", interval=" + tickInterval);
    }

    private void refreshTemptStatus(Player player) {
        UUID playerId = player.getUniqueId();
        if (!enabled) {
            activeTempters.remove(playerId);
            activeTemptDefinitions.remove(playerId);
            return;
        }
        PetFoodConfig.PetFoodDefinition definition = getHeldTemptFood(player).orElse(null);
        if (definition != null) {
            activeTempters.add(playerId);
            activeTemptDefinitions.put(playerId, definition);
        } else {
            activeTempters.remove(playerId);
            activeTemptDefinitions.remove(playerId);
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        refreshTemptStatusNextTick(event.getPlayer());
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        refreshTemptStatusNextTick(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        activeTempters.remove(playerId);
        activeTemptDefinitions.remove(playerId);
    }

    private void tickTemptGoals() {
        if (!enabled) return;
        if (activeTempters.isEmpty()) return;

        java.util.List<UUID> snapshot = java.util.List.copyOf(activeTempters);
        int size = snapshot.size();
        int budget = Math.min(tickBudget, size);
        int start = tickCursor >= size ? 0 : tickCursor;

        for (int processed = 0; processed < budget; processed++) {
            UUID playerId = snapshot.get((start + processed) % size);
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                activeTempters.remove(playerId);
                activeTemptDefinitions.remove(playerId);
                continue;
            }

            PetFoodConfig.PetFoodDefinition definition = activeTemptDefinitions.get(playerId);
            if (definition == null) {
                activeTempters.remove(playerId);
                activeTemptDefinitions.remove(playerId);
                continue;
            }

            Location location = player.getLocation();
            for (Entity nearby : player.getNearbyEntities(definition.temptRange, definition.temptRange, definition.temptRange)) {
                if (!(nearby instanceof Mob mob) || !definition.entities.contains(mob.getType())) {
                    continue;
                }

                if (mob.getLocation().distanceSquared(location) > definition.temptRangeSquared) {
                    continue;
                }

                if (definition.temptIgnoreOwnedTamed && mob.getTarget() == null
                        && mob instanceof AbstractHorse horse && horse.isTamed() && horse.getOwner() == player) {
                    continue;
                }

                tryMoveToPlayer(mob, player, definition.temptMoveSpeed);
            }
        }
        tickCursor = size == 0 ? 0 : (start + Math.max(1, budget)) % size;
    }

    private boolean isHoldingTemptFood(Player player) {
        return getHeldTemptFood(player).isPresent();
    }

    private Optional<PetFoodConfig.PetFoodDefinition> getHeldTemptFood(Player player) {
        return getTemptFood(player.getInventory().getItemInMainHand())
                .or(() -> getTemptFood(player.getInventory().getItemInOffHand()));
    }

    private Optional<PetFoodConfig.PetFoodDefinition> getTemptFood(ItemStack item) {
        String customId = item == null ? null : ItemUtils.getCustomItemId(item);
        if (customId == null) {
            return Optional.empty();
        }

        PetFoodConfig.PetFoodDefinition definition = temptFoods.get(customId.toLowerCase(Locale.ROOT));
        if (definition == null) {
            return Optional.empty();
        }
        return Optional.of(definition);
    }

    private void tryMoveToPlayer(Mob mob, Player player, double moveSpeed) {
        if (player.getGameMode() == GameMode.SPECTATOR || !player.isValid() || player.isDead()) {
            return;
        }

        try {
            mob.getPathfinder().moveTo(player, moveSpeed);
        } catch (Throwable ignored) {
        }
    }

    private void refreshTemptStatusNextTick(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                refreshTemptStatus(player);
            }
        });
    }

}

