package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import it.unimi.dsi.fastutil.ints.IntList;
import net.momirealms.craftengine.bukkit.entity.data.BaseEntityData;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypeProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyItemDisplayManager implements Listener, ItemDisplayManager {

    private static final double VIEW_DISTANCE = 64.0D;
    private static final double VIEW_DISTANCE_SQUARED = VIEW_DISTANCE * VIEW_DISTANCE;
    private static final int DEFAULT_SYNC_INTERVAL_TICKS = 20;
    private static final int DEFAULT_SYNC_BATCH_SIZE = 256;

    private final FarmersDelightPlugin plugin;
    private final BukkitNetworkManager networkManager;
    private final AtomicInteger nextEntityId = new AtomicInteger(2_000_000);
    private final Map<Integer, ProxyItemDisplay> displays = new ConcurrentHashMap<>();
    private BukkitTask syncTask;
    private int syncIntervalTicks = DEFAULT_SYNC_INTERVAL_TICKS;
    private int syncBatchSize = DEFAULT_SYNC_BATCH_SIZE;
    private int syncCursor;

    public ProxyItemDisplayManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        BukkitCraftEngine craftEngine = BukkitCraftEngine.instance();
        if (craftEngine != null) {
            this.networkManager = craftEngine.networkManager();
        } else {
            this.networkManager = null;
        }
        if (isAvailable()) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            reload();
            startSyncTask();
        }
    }

    @Override
    public boolean isAvailable() {
        return networkManager != null;
    }

    public void reload() {
        syncIntervalTicks = Math.max(1, plugin.getConfig().getInt(
                "performance.proxy-item-display-sync-interval-ticks", DEFAULT_SYNC_INTERVAL_TICKS));
        syncBatchSize = Math.max(1, plugin.getConfig().getInt(
                "performance.proxy-item-display-sync-batch-size", DEFAULT_SYNC_BATCH_SIZE));
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
            startSyncTask();
        }
    }

    @Override
    public int createDisplay(DisplaySpec spec) {
        if (!isAvailable() || spec == null || spec.location() == null || spec.location().getWorld() == null) {
            return -1;
        }

        DisplaySpec normalizedSpec = normalize(spec);
        int entityId = nextEntityId.getAndIncrement();
        ProxyItemDisplay display = new ProxyItemDisplay(entityId, UUID.randomUUID(), normalizedSpec);
        displays.put(entityId, display);
        syncDisplay(display);
        return entityId;
    }

    @Override
    public void destroyDisplay(int entityId) {
        ProxyItemDisplay removed = displays.remove(entityId);
        if (removed != null) {
            destroyForAllViewers(removed);
        }
    }

    @Override
    public void cleanupWorld(UUID worldId) {
        if (worldId == null) {
            return;
        }

        List<Integer> toRemove = new ArrayList<>();
        for (Map.Entry<Integer, ProxyItemDisplay> entry : displays.entrySet()) {
            Location location = entry.getValue().spec.location();
            if (location.getWorld() != null && location.getWorld().getUID().equals(worldId)) {
                toRemove.add(entry.getKey());
            }
        }

        for (Integer entityId : toRemove) {
            destroyDisplay(entityId);
        }
    }

    @Override
    public int cleanup() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }

        int removed = displays.size();
        for (Integer entityId : new ArrayList<>(displays.keySet())) {
            destroyDisplay(entityId);
        }
        return removed;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> syncPlayer(event.getPlayer()), 5L);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> syncPlayer(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        for (ProxyItemDisplay display : displays.values()) {
            display.viewers.remove(playerId);
        }
    }

    private void startSyncTask() {
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::syncAll, syncIntervalTicks, syncIntervalTicks);
    }

    private void syncAll() {
        if (!isAvailable()) {
            return;
        }
        if (displays.isEmpty()) {
            return;
        }

        List<ProxyItemDisplay> snapshot = new ArrayList<>(displays.values());
        int size = snapshot.size();
        if (size == 0) {
            syncCursor = 0;
            return;
        }

        Map<UUID, List<Player>> playersByWorld = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            playersByWorld.put(world.getUID(), List.copyOf(world.getPlayers()));
        }

        int budget = Math.min(syncBatchSize, size);
        int start = syncCursor >= size ? 0 : syncCursor;
        int processed = 0;
        for (int i = 0; i < budget; i++) {
            ProxyItemDisplay display = snapshot.get((start + i) % size);
            World world = display.spec.location().getWorld();
            List<Player> players = world == null ? List.of() : playersByWorld.getOrDefault(world.getUID(), List.of());
            syncDisplay(display, players);
            processed++;
        }
        syncCursor = (start + Math.max(1, processed)) % size;
    }

    private void syncPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        for (ProxyItemDisplay display : displays.values()) {
            if (shouldViewerSeeDisplay(player, display)) {
                if (!display.viewers.contains(player.getUniqueId())) {
                    spawnForViewer(player, display);
                }
            } else if (display.viewers.contains(player.getUniqueId())) {
                destroyForViewer(player, display);
            }
        }
    }

    private void syncDisplay(ProxyItemDisplay display) {
        World world = display.spec.location().getWorld();
        List<Player> players = world == null ? List.of() : List.copyOf(world.getPlayers());
        syncDisplay(display, players);
    }

    private void syncDisplay(ProxyItemDisplay display, List<Player> players) {
        World world = display.spec.location().getWorld();
        if (world == null || !world.isChunkLoaded(display.spec.location().getBlockX() >> 4, display.spec.location().getBlockZ() >> 4)) {
            destroyForAllViewers(display);
            return;
        }

        Set<UUID> desiredViewers = new HashSet<>();
        for (Player player : players) {
            if (!shouldViewerSeeDisplay(player, display)) {
                continue;
            }

            desiredViewers.add(player.getUniqueId());
            if (!display.viewers.contains(player.getUniqueId())) {
                spawnForViewer(player, display);
            }
        }

        for (UUID viewerId : new HashSet<>(display.viewers)) {
            if (!desiredViewers.contains(viewerId)) {
                Player player = Bukkit.getPlayer(viewerId);
                if (player != null) {
                    destroyForViewer(player, display);
                } else {
                    display.viewers.remove(viewerId);
                }
            }
        }
    }

    private boolean shouldViewerSeeDisplay(Player player, ProxyItemDisplay display) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        Location location = display.spec.location();
        if (location.getWorld() == null || !Objects.equals(player.getWorld(), location.getWorld())) {
            return false;
        }

        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }

        return player.getLocation().distanceSquared(location) <= VIEW_DISTANCE_SQUARED;
    }

    private void spawnForViewer(Player player, ProxyItemDisplay display) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user == null || !user.isOnline()) {
                return;
            }
            user.sendPackets(List.of(createSpawnPacket(display), createMetadataPacket(display)), false);
            display.viewers.add(player.getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to spawn CE proxy item display " + display.entityId + " for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void destroyForViewer(Player player, ProxyItemDisplay display) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user != null && user.isOnline()) {
                user.sendPacket(createDestroyPacket(display.entityId), false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to destroy CE proxy item display " + display.entityId + " for " + player.getName() + ": " + e.getMessage());
        } finally {
            display.viewers.remove(player.getUniqueId());
        }
    }

    private void destroyForAllViewers(ProxyItemDisplay display) {
        for (UUID viewerId : new HashSet<>(display.viewers)) {
            Player player = Bukkit.getPlayer(viewerId);
            if (player != null) {
                destroyForViewer(player, display);
            } else {
                display.viewers.remove(viewerId);
            }
        }
    }

    private Object createSpawnPacket(ProxyItemDisplay display) {
        Location location = display.spec.location();
        return ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                display.entityId,
                display.entityUuid,
                location.getX(),
                location.getY(),
                location.getZ(),
                0.0F,
                0.0F,
                EntityTypeProxy.ITEM_DISPLAY,
                0,
                Vec3Proxy.ZERO,
                0.0D
        );
    }

    private Object createMetadataPacket(ProxyItemDisplay display) {
        List<Object> values = new ArrayList<>();
        BaseEntityData.NoGravity.addEntityData(true, values);
        BaseEntityData.Silent.addEntityData(true, values);

        var wrappedItem = BukkitItemManager.instance().wrap(display.spec.itemStack());
        if (wrappedItem != null && !wrappedItem.isEmpty()) {
            DisplayData.ItemDisplayData.ItemStack.addEntityData(wrappedItem.minecraftItem(), values);
        }

        var transformation = display.spec.transformation();
        DisplayData.ItemDisplayData.Translation.addEntityData(transformation.getTranslation(), values);
        DisplayData.ItemDisplayData.Scale.addEntityData(transformation.getScale(), values);
        DisplayData.ItemDisplayData.LeftRotation.addEntityData(transformation.getLeftRotation(), values);
        DisplayData.ItemDisplayData.RightRotation.addEntityData(transformation.getRightRotation(), values);
        DisplayData.ItemDisplayData.ItemTransform.addEntityData(toCeDisplayContext(display.spec.itemTransform()), values);
        DisplayData.ItemDisplayData.ShadowRadius.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.ShadowStrength.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.Width.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.Height.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.ViewRange.addEntityData(1.0F, values);
        return createEntityDataPacket(display.entityId, values);
    }

    private Object createEntityDataPacket(int entityId, List<?> values) {
        return ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(entityId, values);
    }

    private Object createDestroyPacket(int entityId) {
        return ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(IntList.of(entityId));
    }

    private byte toCeDisplayContext(ItemDisplay.ItemDisplayTransform transform) {
        if (transform == null) {
            return 0;
        }
        return switch (transform) {
            case THIRDPERSON_LEFTHAND -> 1;
            case THIRDPERSON_RIGHTHAND -> 2;
            case FIRSTPERSON_LEFTHAND -> 3;
            case FIRSTPERSON_RIGHTHAND -> 4;
            case HEAD -> 5;
            case GUI -> 6;
            case GROUND -> 7;
            case FIXED -> 8;
            default -> 0;
        };
    }

    private DisplaySpec normalize(DisplaySpec spec) {
        Location location = spec.location().clone();
        ItemStack itemStack = spec.itemStack().clone();
        itemStack.setAmount(1);
        return new DisplaySpec(location, itemStack, spec.itemTransform(), spec.transformation());
    }

    private static final class ProxyItemDisplay {
        private final int entityId;
        private final UUID entityUuid;
        private final DisplaySpec spec;
        private final Set<UUID> viewers = new HashSet<>();

        private ProxyItemDisplay(int entityId, UUID entityUuid, DisplaySpec spec) {
            this.entityId = entityId;
            this.entityUuid = entityUuid;
            this.spec = spec;
        }
    }
}


