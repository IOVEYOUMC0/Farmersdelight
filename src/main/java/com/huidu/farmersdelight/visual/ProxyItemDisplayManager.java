package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import it.unimi.dsi.fastutil.ints.IntList;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundTeleportEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.syncher.SynchedEntityDataProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypeProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.PositionMoveRotationProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import net.momirealms.craftengine.core.util.MiscUtils;
import net.momirealms.craftengine.core.util.VersionHelper;
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
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyItemDisplayManager implements Listener, ItemDisplayManager {

    private static final double DEFAULT_VIEW_DISTANCE = 64.0D;
    private static final int DEFAULT_SYNC_INTERVAL_TICKS = 20;
    private static final int DEFAULT_SYNC_BATCH_SIZE = 256;
    private static final int ENTITY_DATA_NO_GRAVITY = 5;
    private static final int ENTITY_DATA_SILENT = 4;
    private static final int ITEM_DISPLAY_DATA_ITEM_STACK = 23;
    private static final int ITEM_DISPLAY_DATA_TRANSLATION = 11;
    private static final int ITEM_DISPLAY_DATA_SCALE = 12;
    private static final int ITEM_DISPLAY_DATA_LEFT_ROTATION = 13;
    private static final int ITEM_DISPLAY_DATA_RIGHT_ROTATION = 14;
    private static final int ITEM_DISPLAY_DATA_ITEM_TRANSFORM = 24;
    private static final int ITEM_DISPLAY_DATA_SHADOW_RADIUS = 18;
    private static final int ITEM_DISPLAY_DATA_SHADOW_STRENGTH = 19;
    private static final int ITEM_DISPLAY_DATA_WIDTH = 20;
    private static final int ITEM_DISPLAY_DATA_HEIGHT = 21;
    private static final int ITEM_DISPLAY_DATA_VIEW_RANGE = 17;
    private static final EntityDataBindings ENTITY_DATA = EntityDataBindings.resolve();

    private final FarmersDelightPlugin plugin;
    private final BukkitNetworkManager networkManager;
    private final AtomicInteger nextEntityId = new AtomicInteger(2_000_000);
    private final AtomicBoolean metadataFallbackWarningLogged = new AtomicBoolean();
    private final Map<Integer, ProxyItemDisplay> displays = new ConcurrentHashMap<>();
    private final Map<UUID, Player> onlinePlayers = new ConcurrentHashMap<>();
    private final Set<Integer> scheduledDisplaySyncs = ConcurrentHashMap.newKeySet();
    private final AtomicLong displaySnapshotVersion = new AtomicLong();
    private volatile List<ProxyItemDisplay> displaySnapshot = List.of();
    private volatile long displaySnapshotCachedVersion = -1L;
    private PluginTask syncTask;
    private double viewDistance = DEFAULT_VIEW_DISTANCE;
    private double viewDistanceSquared = DEFAULT_VIEW_DISTANCE * DEFAULT_VIEW_DISTANCE;
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
            for (Player player : Bukkit.getOnlinePlayers()) {
                onlinePlayers.put(player.getUniqueId(), player);
            }
            reload();
            startSyncTask();
        }
    }

    @Override
    public boolean isAvailable() {
        return networkManager != null;
    }

    public void reload() {
        viewDistance = Math.max(8.0D, plugin.getConfig().getDouble(
                "performance.proxy-item-display-view-distance", DEFAULT_VIEW_DISTANCE));
        viewDistanceSquared = viewDistance * viewDistance;
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
        UUID entityUuid = UUID.randomUUID();
        Object spawnPacket = createSpawnPacket(entityId, entityUuid, normalizedSpec);
        Object metadataPacket = createMetadataPacket(entityId, normalizedSpec);
        Object destroyPacket = createDestroyPacket(entityId);
        ProxyItemDisplay display = new ProxyItemDisplay(
                entityId,
                entityUuid,
                normalizedSpec,
                spawnPacket,
                metadataPacket,
                destroyPacket
        );
        displays.put(entityId, display);
        markDisplaySnapshotDirty();
        scheduleSyncDisplay(display);
        return entityId;
    }

    @Override
    public boolean updateDisplay(int entityId, DisplaySpec spec) {
        if (!isAvailable() || spec == null || spec.location() == null || spec.location().getWorld() == null) {
            return false;
        }

        ProxyItemDisplay display = displays.get(entityId);
        if (display == null) {
            return false;
        }

        DisplaySpec normalizedSpec = normalize(spec);
        display.spec = normalizedSpec;
        display.spawnPacket = createSpawnPacket(entityId, display.entityUuid, normalizedSpec);
        display.metadataPacket = createMetadataPacket(entityId, normalizedSpec);
        display.spawnPackets = List.of(display.spawnPacket, display.metadataPacket);
        Object positionPacket = createPositionPacket(entityId, normalizedSpec.location());
        sendUpdateForAllViewers(display, positionPacket, display.metadataPacket);
        scheduleSyncDisplay(display);
        return true;
    }

    @Override
    public void destroyDisplay(int entityId) {
        scheduledDisplaySyncs.remove(entityId);
        ProxyItemDisplay removed = displays.remove(entityId);
        if (removed != null) {
            markDisplaySnapshotDirty();
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
        scheduledDisplaySyncs.clear();
        onlinePlayers.clear();
        displaySnapshot = List.of();
        markDisplaySnapshotDirty();
        return removed;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 5L);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        clearViewer(event.getPlayer().getUniqueId());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        clearViewer(event.getPlayer().getUniqueId());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        onlinePlayers.remove(event.getPlayer().getUniqueId());
        clearViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        UUID worldId = event.getWorld().getUID();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        List<Integer> toRemove = new ArrayList<>();
        for (ProxyItemDisplay display : displays.values()) {
            Location location = display.spec.location();
            if (location.getWorld() == null || !location.getWorld().getUID().equals(worldId)) {
                continue;
            }
            if ((location.getBlockX() >> 4) == chunkX && (location.getBlockZ() >> 4) == chunkZ) {
                toRemove.add(display.entityId);
            }
        }
        for (Integer entityId : toRemove) {
            destroyDisplay(entityId);
        }
    }

    private void startSyncTask() {
        syncTask = plugin.scheduler().runRepeating(this::syncAll, syncIntervalTicks, syncIntervalTicks);
    }

    private void syncAll() {
        if (!isAvailable()) {
            return;
        }
        if (displays.isEmpty()) {
            return;
        }

        List<ProxyItemDisplay> snapshot = getDisplaySnapshot();
        int size = snapshot.size();
        if (size == 0) {
            syncCursor = 0;
            return;
        }

        int budget = Math.min(syncBatchSize, size);
        int start = syncCursor >= size ? 0 : syncCursor;
        int processed = 0;
        for (int i = 0; i < budget; i++) {
            ProxyItemDisplay display = snapshot.get((start + i) % size);
            scheduleSyncDisplay(display);
            processed++;
        }
        syncCursor = (start + Math.max(1, processed)) % size;
    }

    private void markDisplaySnapshotDirty() {
        displaySnapshotVersion.incrementAndGet();
    }

    private List<ProxyItemDisplay> getDisplaySnapshot() {
        long version = displaySnapshotVersion.get();
        List<ProxyItemDisplay> snapshot = displaySnapshot;
        if (displaySnapshotCachedVersion == version) {
            return snapshot;
        }

        List<ProxyItemDisplay> refreshed = new ArrayList<>(displays.values());
        List<ProxyItemDisplay> updated = refreshed.isEmpty() ? List.of() : Collections.unmodifiableList(refreshed);
        displaySnapshot = updated;
        displaySnapshotCachedVersion = version;
        return updated;
    }

    private void scheduleSyncDisplay(ProxyItemDisplay display) {
        if (display == null) {
            return;
        }

        if (!plugin.scheduler().isFolia()) {
            syncDisplay(display);
            return;
        }

        if (!scheduledDisplaySyncs.add(display.entityId)) {
            return;
        }
        try {
            scheduleDisplayForPlayers(display);
        } finally {
            scheduledDisplaySyncs.remove(display.entityId);
        }
    }

    private void scheduleDisplayForPlayers(ProxyItemDisplay display) {
        if (displays.get(display.entityId) != display) {
            return;
        }
        for (Player player : onlinePlayers.values()) {
            if (player == null) {
                continue;
            }
            try {
                plugin.scheduler().runForEntity(player, () -> syncDisplayForPlayer(display, player));
            } catch (RuntimeException e) {
                onlinePlayers.remove(player.getUniqueId());
                display.viewers.remove(player.getUniqueId());
            }
        }
    }

    private void syncPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        onlinePlayers.put(player.getUniqueId(), player);
        for (ProxyItemDisplay display : displays.values()) {
            syncDisplayForPlayer(display, player);
        }
    }

    private void syncDisplayForPlayer(ProxyItemDisplay display, Player player) {
        if (display == null || displays.get(display.entityId) != display) {
            return;
        }
        UUID playerId = player == null ? null : player.getUniqueId();
        if (playerId == null || !onlinePlayers.containsKey(playerId)) {
            if (playerId != null) {
                display.viewers.remove(playerId);
            }
            return;
        }

        if (shouldViewerSeeDisplay(player, display)) {
            if (!display.viewers.contains(playerId)) {
                spawnForViewer(player, display);
            }
        } else if (display.viewers.contains(playerId)) {
            destroyForViewer(player, display);
        }
    }

    private void syncDisplay(ProxyItemDisplay display) {
        if (displays.get(display.entityId) != display) {
            return;
        }
        World world = display.spec.location().getWorld();
        List<Player> players = world == null ? List.of() : world.getPlayers();
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

        if (!plugin.scheduler().isFolia()
                && !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }

        return player.getLocation().distanceSquared(location) <= viewDistanceSquared;
    }

    private void spawnForViewer(Player player, ProxyItemDisplay display) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user == null || !user.isOnline()) {
                return;
            }
            user.sendPackets(display.spawnPackets, false);
            display.viewers.add(player.getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("visual.proxy_spawn_failed",
                    "entity", display.entityId,
                    "player", player.getName(),
                    "error", e.getMessage()));
        }
    }

    private void destroyForViewer(Player player, ProxyItemDisplay display) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user != null && user.isOnline()) {
                user.sendPacket(display.destroyPacket, false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("visual.proxy_destroy_failed",
                    "entity", display.entityId,
                    "player", player.getName(),
                    "error", e.getMessage()));
        } finally {
            display.viewers.remove(player.getUniqueId());
        }
    }

    private void destroyForAllViewers(ProxyItemDisplay display) {
        for (UUID viewerId : new HashSet<>(display.viewers)) {
            Player player = onlinePlayers.get(viewerId);
            if (player != null) {
                if (plugin.scheduler().isFolia()) {
                    try {
                        plugin.scheduler().runForEntity(player, () -> {
                            if (display.viewers.contains(viewerId)) {
                                destroyForViewer(player, display);
                            }
                        });
                    } catch (RuntimeException e) {
                        display.viewers.remove(viewerId);
                        onlinePlayers.remove(viewerId);
                    }
                } else {
                    destroyForViewer(player, display);
                }
            } else {
                display.viewers.remove(viewerId);
            }
        }
    }

    private void sendUpdateForAllViewers(ProxyItemDisplay display, Object positionPacket, Object metadataPacket) {
        for (UUID viewerId : new HashSet<>(display.viewers)) {
            Player player = onlinePlayers.get(viewerId);
            if (player != null) {
                if (plugin.scheduler().isFolia()) {
                    try {
                        plugin.scheduler().runForEntity(player, () -> {
                            if (display.viewers.contains(viewerId)) {
                                sendUpdateForViewer(player, display, positionPacket, metadataPacket);
                            }
                        });
                    } catch (RuntimeException e) {
                        display.viewers.remove(viewerId);
                        onlinePlayers.remove(viewerId);
                    }
                } else {
                    sendUpdateForViewer(player, display, positionPacket, metadataPacket);
                }
            } else {
                display.viewers.remove(viewerId);
            }
        }
    }

    private void sendUpdateForViewer(Player player, ProxyItemDisplay display, Object positionPacket, Object metadataPacket) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user == null || !user.isOnline()) {
                display.viewers.remove(player.getUniqueId());
                return;
            }
            user.sendPackets(List.of(positionPacket, metadataPacket), false);
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("visual.proxy_update_failed",
                    "entity", display.entityId,
                    "player", player.getName(),
                    "error", e.getMessage()));
        }
    }

    private Object createSpawnPacket(int entityId, UUID entityUuid, DisplaySpec spec) {
        Location location = spec.location();
        return ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                entityId,
                entityUuid,
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

    private Object createPositionPacket(int entityId, Location location) {
        if (VersionHelper.isOrAbove1_21_2) {
            Object position = Vec3Proxy.INSTANCE.newInstance(location.getX(), location.getY(), location.getZ());
            Object values = PositionMoveRotationProxy.INSTANCE.newInstance(position, Vec3Proxy.ZERO, 0.0F, 0.0F);
            return ClientboundEntityPositionSyncPacketProxy.INSTANCE.newInstance(entityId, values, false);
        }

        Object packet = ClientboundTeleportEntityPacketProxy.UNSAFE_CONSTRUCTOR.newInstance();
        ClientboundTeleportEntityPacketProxy.INSTANCE.setId(packet, entityId);
        ClientboundTeleportEntityPacketProxy.INSTANCE.setX(packet, location.getX());
        ClientboundTeleportEntityPacketProxy.INSTANCE.setY(packet, location.getY());
        ClientboundTeleportEntityPacketProxy.INSTANCE.setZ(packet, location.getZ());
        ClientboundTeleportEntityPacketProxy.INSTANCE.setYRot(packet, MiscUtils.packDegrees(0.0F));
        ClientboundTeleportEntityPacketProxy.INSTANCE.setXRot(packet, MiscUtils.packDegrees(0.0F));
        ClientboundTeleportEntityPacketProxy.INSTANCE.setOnGround(packet, false);
        return packet;
    }

    private Object createMetadataPacket(int entityId, DisplaySpec spec) {
        List<Object> values = new ArrayList<>();
        boolean usedFallback = false;
        usedFallback |= ENTITY_DATA.noGravity.addTo(values, true);
        usedFallback |= ENTITY_DATA.silent.addTo(values, true);

        var wrappedItem = BukkitItemManager.instance().wrap(spec.itemStack());
        if (wrappedItem != null && !wrappedItem.isEmpty()) {
            usedFallback |= ENTITY_DATA.itemStack.addTo(values, wrappedItem.minecraftItem());
        }

        var transformation = spec.transformation();
        usedFallback |= ENTITY_DATA.translation.addTo(values, transformation.getTranslation());
        usedFallback |= ENTITY_DATA.scale.addTo(values, transformation.getScale());
        usedFallback |= ENTITY_DATA.leftRotation.addTo(values, transformation.getLeftRotation());
        usedFallback |= ENTITY_DATA.rightRotation.addTo(values, transformation.getRightRotation());
        usedFallback |= ENTITY_DATA.itemTransform.addTo(values, toCeDisplayContext(spec.itemTransform()));
        usedFallback |= ENTITY_DATA.shadowRadius.addTo(values, 0.0F);
        usedFallback |= ENTITY_DATA.shadowStrength.addTo(values, 0.0F);
        usedFallback |= ENTITY_DATA.width.addTo(values, 0.0F);
        usedFallback |= ENTITY_DATA.height.addTo(values, 0.0F);
        usedFallback |= ENTITY_DATA.viewRange.addTo(values, 1.0F);
        if (usedFallback) {
            warnMetadataFallback();
        }
        return createEntityDataPacket(entityId, values);
    }

    private Object createEntityDataPacket(int entityId, List<?> values) {
        return ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(entityId, values);
    }

    private Object createDestroyPacket(int entityId) {
        return ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(IntList.of(entityId));
    }

    private void warnMetadataFallback() {
        if (metadataFallbackWarningLogged.compareAndSet(false, true)) {
            plugin.getLogger().warning("CraftEngine ItemDisplay entity data helper is unavailable or failed; "
                    + "using protocol metadata fallback. Verify ItemDisplay visuals after CraftEngine or Minecraft updates.");
        }
    }

    private void clearViewer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        for (ProxyItemDisplay display : displays.values()) {
            display.viewers.remove(playerId);
        }
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

    private static final class EntityDataBindings {
        private static final String BASE_ENTITY_DATA_CLASS =
                "net.momirealms.craftengine.bukkit.entity.data.BaseEntityData";
        private static final String CURRENT_ITEM_DISPLAY_DATA_CLASS =
                "net.momirealms.craftengine.bukkit.entity.data.DisplayData$ItemDisplayData";

        private final EntityDataBinding noGravity;
        private final EntityDataBinding silent;
        private final EntityDataBinding itemStack;
        private final EntityDataBinding translation;
        private final EntityDataBinding scale;
        private final EntityDataBinding leftRotation;
        private final EntityDataBinding rightRotation;
        private final EntityDataBinding itemTransform;
        private final EntityDataBinding shadowRadius;
        private final EntityDataBinding shadowStrength;
        private final EntityDataBinding width;
        private final EntityDataBinding height;
        private final EntityDataBinding viewRange;

        private EntityDataBindings(EntityDataBinding noGravity,
                                   EntityDataBinding silent,
                                   EntityDataBinding itemStack,
                                   EntityDataBinding translation,
                                   EntityDataBinding scale,
                                   EntityDataBinding leftRotation,
                                   EntityDataBinding rightRotation,
                                   EntityDataBinding itemTransform,
                                   EntityDataBinding shadowRadius,
                                   EntityDataBinding shadowStrength,
                                   EntityDataBinding width,
                                   EntityDataBinding height,
                                   EntityDataBinding viewRange) {
            this.noGravity = noGravity;
            this.silent = silent;
            this.itemStack = itemStack;
            this.translation = translation;
            this.scale = scale;
            this.leftRotation = leftRotation;
            this.rightRotation = rightRotation;
            this.itemTransform = itemTransform;
            this.shadowRadius = shadowRadius;
            this.shadowStrength = shadowStrength;
            this.width = width;
            this.height = height;
            this.viewRange = viewRange;
        }

        private static EntityDataBindings resolve() {
            return new EntityDataBindings(
                    base("NoGravity", ENTITY_DATA_NO_GRAVITY, SerializerBinding.BOOLEAN),
                    base("Silent", ENTITY_DATA_SILENT, SerializerBinding.BOOLEAN),
                    itemDisplay("ItemStack", ITEM_DISPLAY_DATA_ITEM_STACK, SerializerBinding.ITEM_STACK),
                    itemDisplay("Translation", ITEM_DISPLAY_DATA_TRANSLATION, SerializerBinding.VECTOR3),
                    itemDisplay("Scale", ITEM_DISPLAY_DATA_SCALE, SerializerBinding.VECTOR3),
                    itemDisplay("LeftRotation", ITEM_DISPLAY_DATA_LEFT_ROTATION, SerializerBinding.QUATERNION),
                    itemDisplay("RightRotation", ITEM_DISPLAY_DATA_RIGHT_ROTATION, SerializerBinding.QUATERNION),
                    itemDisplay("ItemTransform", ITEM_DISPLAY_DATA_ITEM_TRANSFORM, SerializerBinding.BYTE),
                    itemDisplay("ShadowRadius", ITEM_DISPLAY_DATA_SHADOW_RADIUS, SerializerBinding.FLOAT),
                    itemDisplay("ShadowStrength", ITEM_DISPLAY_DATA_SHADOW_STRENGTH, SerializerBinding.FLOAT),
                    itemDisplay("Width", ITEM_DISPLAY_DATA_WIDTH, SerializerBinding.FLOAT),
                    itemDisplay("Height", ITEM_DISPLAY_DATA_HEIGHT, SerializerBinding.FLOAT),
                    itemDisplay("ViewRange", ITEM_DISPLAY_DATA_VIEW_RANGE, SerializerBinding.FLOAT)
            );
        }

        private static EntityDataBinding base(String fieldName, int fallbackId, SerializerBinding fallbackSerializer) {
            return new EntityDataBinding(readEntityData(BASE_ENTITY_DATA_CLASS, fieldName), fallbackId, fallbackSerializer);
        }

        private static EntityDataBinding itemDisplay(String fieldName, int fallbackId, SerializerBinding fallbackSerializer) {
            return new EntityDataBinding(readEntityData(CURRENT_ITEM_DISPLAY_DATA_CLASS, fieldName),
                    fallbackId, fallbackSerializer);
        }

        private static Object readEntityData(String className, String fieldName) {
            try {
                Class<?> dataClass = Class.forName(className, true, BukkitCraftEngine.class.getClassLoader());
                Field field = dataClass.getField(fieldName);
                return field.get(null);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return null;
            }
        }
    }

    private static final class EntityDataBinding {
        private final Object entityData;
        private final Method addEntityDataMethod;
        private final int fallbackId;
        private final SerializerBinding fallbackSerializer;

        private EntityDataBinding(Object entityData, int fallbackId, SerializerBinding fallbackSerializer) {
            this.entityData = entityData;
            this.addEntityDataMethod = resolveAddEntityDataMethod(entityData);
            this.fallbackId = fallbackId;
            this.fallbackSerializer = fallbackSerializer;
        }

        private boolean addTo(List<Object> values, Object value) {
            if (entityData != null && addEntityDataMethod != null) {
                try {
                    addEntityDataMethod.invoke(entityData, value, values);
                    return false;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Fall through to the protocol-level DataValue path below.
                }
            }
            values.add(SynchedEntityDataProxy.DataValueProxy.INSTANCE.newInstance(fallbackId, fallbackSerializer.resolve(), value));
            return true;
        }

        private static Method resolveAddEntityDataMethod(Object entityData) {
            if (entityData == null) {
                return null;
            }
            try {
                return entityData.getClass().getMethod("addEntityData", Object.class, List.class);
            } catch (NoSuchMethodException | SecurityException ignored) {
                return null;
            }
        }
    }

    private static final class SerializerBinding {
        private static final String SERIALIZERS_CLASS =
                "net.momirealms.craftengine.proxy.minecraft.network.syncher.EntityDataSerializersProxy";
        private static final SerializerBinding BOOLEAN = new SerializerBinding("BOOLEAN", "getBoolean");
        private static final SerializerBinding BYTE = new SerializerBinding("BYTE", "getByte");
        private static final SerializerBinding FLOAT = new SerializerBinding("FLOAT", "getFloat");
        private static final SerializerBinding ITEM_STACK = new SerializerBinding("ITEM_STACK", "getItemStack");
        private static final SerializerBinding QUATERNION = new SerializerBinding("QUATERNION", "getQuaternion");
        private static final SerializerBinding VECTOR3 = new SerializerBinding("VECTOR3", "getVector3");

        private final String fieldName;
        private final String getterName;
        private volatile Object serializer;

        private SerializerBinding(String fieldName, String getterName) {
            this.fieldName = fieldName;
            this.getterName = getterName;
        }

        private Object resolve() {
            Object resolved = serializer;
            if (resolved != null) {
                return resolved;
            }

            resolved = resolveByGetter();
            if (resolved == null) {
                resolved = resolveByStaticField();
            }
            if (resolved == null) {
                throw new IllegalStateException("CraftEngine EntityDataSerializersProxy serializer is unavailable: " + fieldName);
            }
            serializer = resolved;
            return resolved;
        }

        private Object resolveByGetter() {
            try {
                Class<?> serializersClass = Class.forName(SERIALIZERS_CLASS, true, BukkitCraftEngine.class.getClassLoader());
                Object instance = serializersClass.getField("INSTANCE").get(null);
                Method getter = serializersClass.getMethod(getterName);
                return getter.invoke(instance);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return null;
            }
        }

        private Object resolveByStaticField() {
            try {
                Class<?> serializersClass = Class.forName(SERIALIZERS_CLASS, true, BukkitCraftEngine.class.getClassLoader());
                return serializersClass.getField(fieldName).get(null);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return null;
            }
        }
    }

    private static final class ProxyItemDisplay {
        private final int entityId;
        private final UUID entityUuid;
        private DisplaySpec spec;
        private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
        private Object spawnPacket;
        private Object metadataPacket;
        private final Object destroyPacket;
        private List<Object> spawnPackets;

        private ProxyItemDisplay(int entityId, UUID entityUuid, DisplaySpec spec,
                                 Object spawnPacket, Object metadataPacket, Object destroyPacket) {
            this.entityId = entityId;
            this.entityUuid = entityUuid;
            this.spec = spec;
            this.spawnPacket = spawnPacket;
            this.metadataPacket = metadataPacket;
            this.destroyPacket = destroyPacket;
            this.spawnPackets = List.of(spawnPacket, metadataPacket);
        }
    }
}
