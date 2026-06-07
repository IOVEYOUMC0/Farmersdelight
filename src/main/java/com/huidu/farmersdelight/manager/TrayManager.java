package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TrayManager {

    private static final long DEFAULT_SYNC_INTERVAL_TICKS = 100L;
    private static final int DEFAULT_SYNC_BATCH_SIZE = 32;
    private static final long DEFAULT_MARKED_TRAY_SCAN_INTERVAL_TICKS = 1200L;
    private static final String NAMESPACE = "farmersdelight";
    private static final String DEFAULT_MARKER_KEY = "auto_tray_marker";
    private static final String MARKER_SCOREBOARD_PREFIX = "farmersdelight:auto_tray:";
    private static final String OWNER_SCOREBOARD_PREFIX = "farmersdelight:auto_tray_owner:";

    private final FarmersDelightPlugin plugin;
    private final Map<UUID, World> knownWorlds = new ConcurrentHashMap<>();
    private final Set<TraySyncKey> scheduledTraySyncs = ConcurrentHashMap.newKeySet();
    private final Queue<Location> queuedTraySyncs = new ConcurrentLinkedQueue<>();
    private final Set<TraySyncKey> queuedTraySyncKeys = ConcurrentHashMap.newKeySet();
    private final NamespacedKey defaultTrayMarkerKey = new NamespacedKey(NAMESPACE, DEFAULT_MARKER_KEY);
    private final NamespacedKey trayOwnerWorldKey = new NamespacedKey(NAMESPACE, "auto_tray_owner_world");
    private final NamespacedKey trayOwnerXKey = new NamespacedKey(NAMESPACE, "auto_tray_owner_x");
    private final NamespacedKey trayOwnerYKey = new NamespacedKey(NAMESPACE, "auto_tray_owner_y");
    private final NamespacedKey trayOwnerZKey = new NamespacedKey(NAMESPACE, "auto_tray_owner_z");
    private String trayFurnitureId;
    private double xOffset;
    private double yOffset;
    private double zOffset;
    private boolean requireNonFullSupport;
    private NamespacedKey trayMarkerKey;
    private String trayScoreboardTag;
    private boolean enabled;
    private long syncIntervalTicks;
    private int syncBatchSize;
    private long markedTrayScanIntervalMillis;
    private PluginTask syncTask;
    private int cookingPotSyncCursor;
    private int skilletSyncCursor;
    private int trayOwnerSyncCursor;
    private long lastTrayOwnerScanMillis;
    private PluginTask queuedSyncTask;
    // Guards queuedSyncTask, which is started/stopped from region-thread queueTraySync callbacks.
    private final Object queuedSyncTaskLock = new Object();

    public TrayManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
        start();
    }

    private void trackWorld(World world) {
        if (world != null) {
            knownWorlds.put(world.getUID(), world);
        }
    }

    private void loadConfig() {
        ConfigurationSection config = plugin.getFirstConfigSection("cooking-pot.tray", "tray");
        if (config == null) {
            // Use a detached empty section so absent config falls back to the per-field defaults
            // below without mutating the live FileConfiguration (createSection would inject an
            // unexpected empty section the user never wrote).
            config = new org.bukkit.configuration.MemoryConfiguration();
        }

        enabled = config.getBoolean("enabled", true);
        trayFurnitureId = config.getString("tray-block-id", "farmersdelight:tray");
        xOffset = config.getDouble("x-offset", 0.5D);
        yOffset = config.getDouble("y-offset", -1.0D);
        zOffset = config.getDouble("z-offset", 0.5D);
        requireNonFullSupport = config.getBoolean("require-non-full-support", true);
        syncIntervalTicks = Math.max(20L, config.getLong("sync-interval-ticks", DEFAULT_SYNC_INTERVAL_TICKS));
        syncBatchSize = Math.max(1, config.getInt("sync-batch-size", DEFAULT_SYNC_BATCH_SIZE));
        long markedTrayScanIntervalTicks = Math.max(0L, config.getLong(
                "marked-tray-scan-interval-ticks",
                DEFAULT_MARKED_TRAY_SCAN_INTERVAL_TICKS));
        markedTrayScanIntervalMillis = markedTrayScanIntervalTicks * 50L;
        String markerKey = config.getString("marker-key", DEFAULT_MARKER_KEY);
        trayMarkerKey = createTrayMarkerKey(markerKey);
        trayScoreboardTag = "farmersdelight:auto_tray:" + trayMarkerKey.getKey();
    }

    private NamespacedKey createTrayMarkerKey(String markerKey) {
        String key = markerKey == null || markerKey.isBlank() ? DEFAULT_MARKER_KEY : markerKey.trim();
        try {
            return new NamespacedKey(NAMESPACE, key);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(I18n.formatConsole("tray.invalid_marker_key", "key", key));
            return defaultTrayMarkerKey;
        }
    }

    public void reload() {
        loadConfig();
        if (!enabled) {
            stop();
            removeAllTrays();
            return;
        }

        clearPendingSyncs();
        resetSyncCursors();
        start();
        syncAllTraysNow();
    }

    private void start() {
        stop();
        syncTask = plugin.scheduler().runRepeating(this::syncAllTraysBatched, 1L, syncIntervalTicks);
    }

    public void stop() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        synchronized (queuedSyncTaskLock) {
            if (queuedSyncTask != null) {
                queuedSyncTask.cancel();
                queuedSyncTask = null;
            }
        }
    }

    private void clearPendingSyncs() {
        scheduledTraySyncs.clear();
        queuedTraySyncs.clear();
        queuedTraySyncKeys.clear();
    }

    private void resetSyncCursors() {
        cookingPotSyncCursor = 0;
        skilletSyncCursor = 0;
        trayOwnerSyncCursor = 0;
    }

    public void checkAndPlaceTray(World world, BlockPos potPos) {
        if (!enabled || world == null || potPos == null) return;
        trackWorld(world);

        if (!shouldHaveTray(world, potPos)) {
            removeTrayIfAutoPlaced(world, potPos);
            return;
        }

        Location trayLoc = getTrayLocation(world, potPos);
        // getMaxHeight() is exclusive (highest placeable Y is getMaxHeight() - 1).
        if (trayLoc.getY() < world.getMinHeight() || trayLoc.getY() >= world.getMaxHeight()) {
            return;
        }

        BlockPos trayPos = new BlockPos(trayLoc.getBlockX(), trayLoc.getBlockY(), trayLoc.getBlockZ());
        List<BukkitFurniture> existingFurnitures = findTrayFurnitures(world, trayLoc);
        BukkitFurniture existingAutoTray = firstAutoTray(existingFurnitures);
        if (existingAutoTray != null) {
            markTrayFurniture(existingAutoTray, world, potPos);
            removeDuplicateAutoTrays(world, trayPos, existingFurnitures, existingAutoTray, "duplicate auto tray before place");
            return;
        }

        if (!existingFurnitures.isEmpty()) {
            // A tray furniture already exists at the auto-tray position but is not marked: its PDC marker
            // was lost when CraftEngine recreated the display entity across a restart/chunk reload.
            // Re-claim it (re-mark + track) so break-removal and break-protection recognize it again.
            BukkitFurniture reclaimed = existingFurnitures.get(0);
            markTrayFurniture(reclaimed, world, potPos);
            removeDuplicateAutoTrays(world, trayPos, existingFurnitures, reclaimed, "reclaim unmarked tray");
            return;
        }

        if (!canPlaceTrayAt(trayLoc.getBlock())) {
            return;
        }

        try {
            BukkitFurniture furniture = CraftEngineFurniture.place(trayLoc, Key.of(trayFurnitureId));
            if (furniture == null) {
                plugin.getLogger().warning(I18n.formatConsole("tray.missing_furniture", "id", trayFurnitureId));
                return;
            }

            markTrayFurniture(furniture, world, potPos);

            if (plugin.isDebugEnabled("tray")) {
                plugin.getLogger().info(I18n.formatConsole("tray.placed", "location", trayLoc, "pot", potPos));
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("tray.place_failed", "error", e.getMessage()));
        }
    }

    public void checkAndPlaceTray(Location cookingBlockLocation) {
        if (cookingBlockLocation == null || cookingBlockLocation.getWorld() == null) {
            return;
        }
        checkAndPlaceTray(
                cookingBlockLocation.getWorld(),
                new BlockPos(cookingBlockLocation.getBlockX(), cookingBlockLocation.getBlockY(), cookingBlockLocation.getBlockZ())
        );
    }

    public void removeTrayIfAutoPlaced(World world, BlockPos potPos) {
        if (!enabled || world == null || potPos == null) return;
        trackWorld(world);

        Location trayLoc = getTrayLocation(world, potPos);
        BlockPos trayPos = new BlockPos(trayLoc.getBlockX(), trayLoc.getBlockY(), trayLoc.getBlockZ());
        removeTrayAt(world, trayPos, "owner removed");
    }

    public void removeTrayIfAutoPlaced(Location cookingBlockLocation) {
        if (cookingBlockLocation == null || cookingBlockLocation.getWorld() == null) {
            return;
        }
        removeTrayIfAutoPlaced(
                cookingBlockLocation.getWorld(),
                new BlockPos(cookingBlockLocation.getBlockX(), cookingBlockLocation.getBlockY(), cookingBlockLocation.getBlockZ())
        );
    }

    public void syncAroundSupportChange(Location supportLocation) {
        if (!enabled || supportLocation == null || supportLocation.getWorld() == null) {
            return;
        }

        Location normalized = new Location(
                supportLocation.getWorld(),
                supportLocation.getBlockX(),
                supportLocation.getBlockY(),
                supportLocation.getBlockZ()
        );
        trackWorld(normalized.getWorld());
        plugin.scheduler().runLaterAt(normalized, () -> {
            queueTraySync(normalized.clone().add(0, 1, 0));
            queueTraySync(normalized.clone().add(0, 2, 0));
        }, 1L);
    }

    public void queueTraySync(Location ownerLocation) {
        if (!enabled || ownerLocation == null || ownerLocation.getWorld() == null) {
            return;
        }

        TraySyncKey key = new TraySyncKey(
                ownerLocation.getWorld().getUID(),
                ownerLocation.getBlockX(),
                ownerLocation.getBlockY(),
                ownerLocation.getBlockZ()
        );
        if (!queuedTraySyncKeys.add(key)) {
            return;
        }

        queuedTraySyncs.add(new Location(ownerLocation.getWorld(), key.x(), key.y(), key.z()));
        trackWorld(ownerLocation.getWorld());
        ensureQueuedSyncTask();
    }

    private void ensureQueuedSyncTask() {
        synchronized (queuedSyncTaskLock) {
            if (queuedSyncTask != null) {
                return;
            }
            queuedSyncTask = plugin.scheduler().runRepeating(this::processQueuedTraySyncs, 1L, 1L);
        }
    }

    private void processQueuedTraySyncs() {
        int budget = Math.max(1, syncBatchSize);
        for (int processed = 0; processed < budget; processed++) {
            Location location = queuedTraySyncs.poll();
            if (location == null) {
                stopQueuedSyncTaskIfIdle();
                return;
            }

            queuedTraySyncKeys.remove(new TraySyncKey(
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            ));
            scheduleTraySync(location);
        }
        stopQueuedSyncTaskIfIdle();
    }

    private void stopQueuedSyncTaskIfIdle() {
        synchronized (queuedSyncTaskLock) {
            if (!queuedTraySyncs.isEmpty() || queuedSyncTask == null) {
                return;
            }
            queuedSyncTask.cancel();
            queuedSyncTask = null;
        }
    }

    private boolean isPotOrSkilletAt(World world, BlockPos pos) {
        if (CookingPotBlockBehavior.isCookingPotBlock(world, new BlockPosKey(pos))) {
            return true;
        }
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        return isSkilletBlock(location);
    }

    private boolean shouldHaveTray(World world, BlockPos potPos) {
        HeatSourceConfig heatConfig = plugin.getHeatSourceConfig();
        if (heatConfig == null) {
            return false;
        }

        Location belowLoc = new Location(world, potPos.x(), potPos.y() - 1, potPos.z());
        Block blockBelow = belowLoc.getBlock();
        if (heatConfig.isHeatSource(blockBelow)) {
            return isValidTraySupport(blockBelow);
        }

        if (heatConfig.isConductor(blockBelow)) {
            Block blockTwoBelow = world.getBlockAt(potPos.x(), potPos.y() - 2, potPos.z());
            return heatConfig.isHeatSource(blockTwoBelow) && isValidTraySupport(blockBelow);
        }

        return false;
    }

    private Location getTrayLocation(World world, BlockPos potPos) {
        return new Location(world, potPos.x() + xOffset, potPos.y() + yOffset, potPos.z() + zOffset);
    }

    private Collection<Location> getSkilletLocations() {
        SkilletManager skilletManager = plugin.getSkilletManager();
        return skilletManager == null ? List.of() : skilletManager.getTrackedLocations();
    }

    private void syncAllTraysBatched() {
        if (!enabled) {
            return;
        }

        Set<TraySyncKey> scheduledThisRun = new HashSet<>(syncBatchSize * 3);
        cookingPotSyncCursor = scheduleBatchedTraySyncs(
                CookingPotBlockBehavior.getBlockEntityLocations(),
                cookingPotSyncCursor,
                true,
                scheduledThisRun
        );

        skilletSyncCursor = scheduleBatchedTraySyncs(
                getSkilletLocations(),
                skilletSyncCursor,
                true,
                scheduledThisRun
        );

        if (!plugin.scheduler().isFolia() && shouldScanTrackedTrayOwners()) {
            trayOwnerSyncCursor = scheduleBatchedTraySyncs(
                    getTrackedTrayOwnerLocations(),
                    trayOwnerSyncCursor,
                    true,
                    scheduledThisRun
            );
        }
    }

    private void syncAllTraysNow() {
        if (!enabled) {
            return;
        }

        Set<TraySyncKey> scheduledThisRun = new HashSet<>();
        scheduleBatchedTraySyncs(CookingPotBlockBehavior.getBlockEntityLocations(), 0, false, scheduledThisRun);
        scheduleBatchedTraySyncs(getSkilletLocations(), 0, false, scheduledThisRun);

        if (!plugin.scheduler().isFolia()) {
            lastTrayOwnerScanMillis = System.currentTimeMillis();
            scheduleBatchedTraySyncs(getTrackedTrayOwnerLocations(), 0, false, scheduledThisRun);
        }
    }

    private boolean shouldScanTrackedTrayOwners() {
        long now = System.currentTimeMillis();
        if (markedTrayScanIntervalMillis <= 0L) {
            lastTrayOwnerScanMillis = now;
            return true;
        }
        if (lastTrayOwnerScanMillis != 0L
                && now - lastTrayOwnerScanMillis < markedTrayScanIntervalMillis) {
            return false;
        }
        lastTrayOwnerScanMillis = now;
        return true;
    }

    private int scheduleBatchedTraySyncs(
            Collection<Location> rawLocations,
            int cursor,
            boolean batched,
            Set<TraySyncKey> scheduledThisRun
    ) {
        if (rawLocations == null || rawLocations.isEmpty()) {
            return 0;
        }

        List<Location> locations = new ArrayList<>(rawLocations);
        int size = locations.size();
        int start = cursor;
        if (start >= size) {
            start = 0;
        }

        int budget = batched ? Math.min(syncBatchSize, size) : size;
        for (int processed = 0; processed < budget; processed++) {
            Location location = locations.get((start + processed) % size);
            scheduleTraySync(location, scheduledThisRun);
        }

        return batched ? (start + Math.max(1, budget)) % size : 0;
    }

    private boolean scheduleTraySync(Location location) {
        return scheduleTraySync(location, null);
    }

    private boolean scheduleTraySync(Location location, @Nullable Set<TraySyncKey> scheduledThisRun) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        TraySyncKey key = new TraySyncKey(
                location.getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
        if (scheduledThisRun != null && !scheduledThisRun.add(key)) {
            return false;
        }

        Location normalized = new Location(
                location.getWorld(),
                key.x(),
                key.y(),
                key.z()
        );
        trackWorld(normalized.getWorld());

        if (!plugin.scheduler().isFolia()) {
            syncTrayOwner(normalized);
            return true;
        }

        if (!scheduledTraySyncs.add(key)) {
            return false;
        }
        try {
            plugin.scheduler().runAt(normalized, () -> {
                try {
                    syncTrayOwner(normalized);
                } finally {
                    scheduledTraySyncs.remove(key);
                }
            });
            return true;
        } catch (RuntimeException e) {
            scheduledTraySyncs.remove(key);
            return false;
        }
    }

    private void syncTrayOwner(Location location) {
        if (!enabled || location == null || location.getWorld() == null) {
            return;
        }

        World world = location.getWorld();
        trackWorld(world);
        BlockPos ownerPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (!isPotOrSkilletAt(world, ownerPos)) {
            removeTrayIfAutoPlaced(world, ownerPos);
            return;
        }

        if (shouldHaveTray(world, ownerPos)) {
            checkAndPlaceTray(world, ownerPos);
        } else {
            removeTrayIfAutoPlaced(world, ownerPos);
        }
    }

    private List<Location> getTrackedTrayOwnerLocations() {
        List<Location> locations = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            trackWorld(world);
            for (BukkitFurniture furniture : findAutoTrayFurnitures(world)) {
                TrayOwner owner = resolveTrayOwner(world, furniture);
                if (owner == null || !owner.worldId().equals(world.getUID())) {
                    continue;
                }
                locations.add(new Location(world, owner.pos().x(), owner.pos().y(), owner.pos().z()));
            }
        }
        return locations;
    }

    private List<Location> getKnownTrayOwnerLocations(@Nullable UUID worldId) {
        List<Location> locations = new ArrayList<>();
        addKnownTrayOwnerLocations(locations, CookingPotBlockBehavior.getBlockEntityLocations(), worldId);
        addKnownTrayOwnerLocations(locations, getSkilletLocations(), worldId);
        return locations;
    }

    private void addKnownTrayOwnerLocations(
            List<Location> target,
            Collection<Location> source,
            @Nullable UUID worldId
    ) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Location location : source) {
            if (location == null || location.getWorld() == null) {
                continue;
            }
            if (worldId != null && !location.getWorld().getUID().equals(worldId)) {
                continue;
            }
            target.add(new Location(
                    location.getWorld(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            ));
        }
    }

    private int scheduleKnownOwnerTraySyncs(@Nullable UUID worldId) {
        Set<TraySyncKey> scheduledThisRun = new HashSet<>();
        int scheduled = 0;
        for (Location location : getKnownTrayOwnerLocations(worldId)) {
            if (scheduleTraySync(location, scheduledThisRun)) {
                scheduled++;
            }
        }
        return scheduled;
    }

    private void removeKnownOwnerTrays(@Nullable UUID worldId, String reason) {
        Set<TraySyncKey> seen = new HashSet<>();
        for (Location location : getKnownTrayOwnerLocations(worldId)) {
            if (location == null || location.getWorld() == null) {
                continue;
            }
            TraySyncKey key = new TraySyncKey(
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
            if (!seen.add(key)) {
                continue;
            }

            World world = location.getWorld();
            BlockPos ownerPos = new BlockPos(key.x(), key.y(), key.z());
            Location trayLoc = getTrayLocation(world, ownerPos);
            BlockPos trayPos = new BlockPos(
                    trayLoc.getBlockX(),
                    trayLoc.getBlockY(),
                    trayLoc.getBlockZ()
            );
            scheduleRemoveTrayAt(world, trayPos, reason);
        }
    }

    private boolean isSkilletBlock(Location location) {
        return CustomBlockUtils.hasBehavior(location, SkilletBlockBehavior.class)
                || CustomBlockUtils.hasId(location, Constants.BLOCK_SKILLET);
    }

    private boolean canPlaceTrayAt(Block block) {
        HeatSourceConfig heatConfig = plugin.getHeatSourceConfig();
        return (heatConfig != null && (heatConfig.isHeatSource(block) || heatConfig.isConductor(block)))
                || isReplaceable(block);
    }

    private boolean isValidTraySupport(Block block) {
        return !requireNonFullSupport || isNonFullSupport(block);
    }

    private boolean isNonFullSupport(Block block) {
        if (block == null) {
            return false;
        }

        Material type = block.getType();
        if (type == Material.HOPPER) {
            return false;
        }

        if (!type.isOccluding()) {
            return true;
        }

        BoundingBox box = block.getBoundingBox();
        double height = box.getMaxY() - box.getMinY();
        return height < 0.99D;
    }

    private boolean isReplaceable(Block block) {
        Material type = block.getType();
        return type.isAir()
                || type == Material.WATER
                || type == Material.SEAGRASS
                || type == Material.TALL_SEAGRASS
                || type == Material.SHORT_GRASS
                || type == Material.TALL_GRASS
                || type == Material.FERN
                || type == Material.LARGE_FERN
                || type == Material.SNOW;
    }

    private void markTrayFurniture(BukkitFurniture furniture, World ownerWorld, BlockPos ownerPos) {
        Entity entity = furniture.bukkitEntity();
        if (entity != null && entity.isValid()) {
            entity.getPersistentDataContainer().set(trayMarkerKey, PersistentDataType.BYTE, (byte) 1);
            entity.getPersistentDataContainer().set(defaultTrayMarkerKey, PersistentDataType.BYTE, (byte) 1);
            entity.getPersistentDataContainer().set(trayOwnerWorldKey, PersistentDataType.STRING, ownerWorld.getUID().toString());
            entity.getPersistentDataContainer().set(trayOwnerXKey, PersistentDataType.INTEGER, ownerPos.x());
            entity.getPersistentDataContainer().set(trayOwnerYKey, PersistentDataType.INTEGER, ownerPos.y());
            entity.getPersistentDataContainer().set(trayOwnerZKey, PersistentDataType.INTEGER, ownerPos.z());
            entity.addScoreboardTag(trayScoreboardTag);
            removeScoreboardTagsWithPrefix(entity, OWNER_SCOREBOARD_PREFIX);
            entity.addScoreboardTag(ownerScoreboardTag(ownerWorld, ownerPos));
        }
    }

    public boolean isAutoPlacedTray(BukkitFurniture furniture) {
        if (furniture == null) {
            return false;
        }
        Entity entity = furniture.bukkitEntity();
        return isAutoPlacedTrayEntity(entity);
    }

    private boolean isAutoPlacedTrayEntity(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return false;
        }
        if (entity.getPersistentDataContainer().has(trayMarkerKey, PersistentDataType.BYTE)
                || entity.getPersistentDataContainer().has(defaultTrayMarkerKey, PersistentDataType.BYTE)) {
            return true;
        }
        for (String tag : entity.getScoreboardTags()) {
            if (tag.equals(trayScoreboardTag) || tag.startsWith(MARKER_SCOREBOARD_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private TrayOwner getTrayOwner(BukkitFurniture furniture) {
        if (furniture == null) {
            return null;
        }
        return getTrayOwner(furniture.bukkitEntity());
    }

    @Nullable
    private TrayOwner getTrayOwner(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return null;
        }
        String worldId = entity.getPersistentDataContainer().get(trayOwnerWorldKey, PersistentDataType.STRING);
        Integer x = entity.getPersistentDataContainer().get(trayOwnerXKey, PersistentDataType.INTEGER);
        Integer y = entity.getPersistentDataContainer().get(trayOwnerYKey, PersistentDataType.INTEGER);
        Integer z = entity.getPersistentDataContainer().get(trayOwnerZKey, PersistentDataType.INTEGER);
        if (worldId != null && x != null && y != null && z != null) {
            try {
                return new TrayOwner(UUID.fromString(worldId), new BlockPos(x, y, z));
            } catch (IllegalArgumentException ignored) {
            }
        }

        for (String tag : entity.getScoreboardTags()) {
            if (!tag.startsWith(OWNER_SCOREBOARD_PREFIX)) {
                continue;
            }
            TrayOwner owner = parseOwnerScoreboardTag(tag);
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    @Nullable
    private TrayOwner parseOwnerScoreboardTag(String tag) {
        String payload = tag.substring(OWNER_SCOREBOARD_PREFIX.length());
        String[] parts = payload.split(":");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new TrayOwner(
                    UUID.fromString(parts[0]),
                    new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]))
            );
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String ownerScoreboardTag(World world, BlockPos ownerPos) {
        return OWNER_SCOREBOARD_PREFIX + world.getUID() + ":" + ownerPos.x() + ":" + ownerPos.y() + ":" + ownerPos.z();
    }

    private void removeScoreboardTagsWithPrefix(Entity entity, String prefix) {
        for (String tag : List.copyOf(entity.getScoreboardTags())) {
            if (tag.startsWith(prefix)) {
                entity.removeScoreboardTag(tag);
            }
        }
    }

    @Nullable
    private TrayOwner resolveTrayOwner(World world, BukkitFurniture furniture) {
        TrayOwner owner = getTrayOwner(furniture);
        if (owner != null) {
            return owner;
        }
        Entity entity = furniture == null ? null : furniture.bukkitEntity();
        if (world == null || entity == null || !entity.isValid()) {
            return null;
        }
        return new TrayOwner(world.getUID(), inferOwnerPos(entity.getLocation()));
    }

    private BlockPos inferOwnerPos(Location trayLocation) {
        return new BlockPos(
                (int) Math.floor(trayLocation.getX() - xOffset + 1.0E-6D),
                (int) Math.floor(trayLocation.getY() - yOffset + 1.0E-6D),
                (int) Math.floor(trayLocation.getZ() - zOffset + 1.0E-6D)
        );
    }

    public int cleanupInvalidAutoTrays() {
        if (plugin.scheduler().isFolia()) {
            return scheduleKnownOwnerTraySyncs(null);
        }

        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            trackWorld(world);
            for (BukkitFurniture furniture : findAutoTrayFurnitures(world)) {
                Entity entity = furniture.bukkitEntity();
                if (entity == null || !entity.isValid()) {
                    continue;
                }
                TrayOwner owner = resolveTrayOwner(world, furniture);
                if (owner != null
                        && owner.worldId().equals(world.getUID())
                        && isValidAutoTrayOwner(world, owner.pos())) {
                    markTrayFurniture(furniture, world, owner.pos());
                    continue;
                }

                BlockPos trayPos = new BlockPos(
                        entity.getLocation().getBlockX(),
                        entity.getLocation().getBlockY(),
                        entity.getLocation().getBlockZ()
                );

                try {
                    removeTrayAt(world, trayPos, "cleanup invalid owner");
                    removed++;
                } catch (Exception e) {
                    plugin.getLogger().warning(I18n.formatConsole("tray.cleanup_invalid_failed",
                            "pos", trayPos,
                            "error", e.getMessage()));
                }
            }
        }
        return removed;
    }

    private boolean isValidAutoTrayOwner(World world, BlockPos ownerPos) {
        return ownerPos != null && isPotOrSkilletAt(world, ownerPos) && shouldHaveTray(world, ownerPos);
    }

    private void removeTrayAt(World world, BlockPos trayPos) {
        removeTrayAt(world, trayPos, "unspecified");
    }

    private void removeTrayAt(World world, BlockPos trayPos, String reason) {
        try {
            Location location = new Location(world, trayPos.x(), trayPos.y(), trayPos.z());
            int removed = 0;
            for (BukkitFurniture furniture : findTrayFurnitures(world, location)) {
                if (!isAutoPlacedTray(furniture)) {
                    // Owner-specific cleanup still reclaims old unmarked tray furniture at the exact
                    // auto-tray position. Global cleanup paths only scan marked trays.
                    if (!"owner removed".equals(reason) && !"cleanup invalid owner".equals(reason)) {
                        continue;
                    }
                }
                Entity entity = furniture.bukkitEntity();
                if (entity != null && entity.isValid()) {
                    CraftEngineFurniture.remove(entity, false, false);
                    removed++;
                }
            }

            if (removed > 0 && plugin.isDebugEnabled("tray")) {
                plugin.getLogger().info(I18n.formatConsole("tray.removed", "pos", trayPos, "reason", reason));
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("tray.remove_failed", "error", e.getMessage()));
        }
    }

    @Nullable
    private BukkitFurniture findAutoTrayFurniture(World world, Location location) {
        List<BukkitFurniture> furnitures = findTrayFurnitures(world, location);
        BukkitFurniture autoTray = firstAutoTray(furnitures);
        if (autoTray != null) {
            removeDuplicateAutoTrays(world, new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                    furnitures, autoTray, "duplicate auto tray found");
        }
        return autoTray;
    }

    private List<BukkitFurniture> findTrayFurnitures(World world, Location location) {
        if (world == null || location == null) {
            return List.of();
        }
        List<BukkitFurniture> furnitures = new ArrayList<>();
        double bx = location.getBlockX();
        double by = location.getBlockY();
        double bz = location.getBlockZ();
        for (Entity entity : world.getNearbyEntities(
                new BoundingBox(bx, by, bz, bx + 1, by + 1, bz + 1))) {
            if (!(entity instanceof org.bukkit.entity.ItemDisplay)) continue;

            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture != null && furniture.id().toString().equals(trayFurnitureId)) {
                furnitures.add(furniture);
            }
        }
        return furnitures;
    }

    private List<BukkitFurniture> findAutoTrayFurnitures(World world) {
        if (world == null) {
            return List.of();
        }
        List<BukkitFurniture> furnitures = new ArrayList<>();
        Set<UUID> seenEntities = new HashSet<>();
        for (Entity entity : world.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class)) {
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture == null || !furniture.id().toString().equals(trayFurnitureId) || !isAutoPlacedTray(furniture)) {
                continue;
            }
            Entity rootEntity = furniture.bukkitEntity();
            if (rootEntity != null && seenEntities.add(rootEntity.getUniqueId())) {
                furnitures.add(furniture);
            }
        }
        return furnitures;
    }

    @Nullable
    private BukkitFurniture firstAutoTray(Collection<BukkitFurniture> furnitures) {
        if (furnitures == null || furnitures.isEmpty()) {
            return null;
        }
        for (BukkitFurniture furniture : furnitures) {
            if (isAutoPlacedTray(furniture)) {
                return furniture;
            }
        }
        return null;
    }

    private void removeDuplicateAutoTrays(World world, BlockPos trayPos, Collection<BukkitFurniture> furnitures,
                                          BukkitFurniture keep, String reason) {
        if (furnitures == null || furnitures.isEmpty()) {
            return;
        }
        for (BukkitFurniture furniture : furnitures) {
            if (furniture == keep || !isAutoPlacedTray(furniture)) {
                continue;
            }
            Entity entity = furniture.bukkitEntity();
            if (entity != null && entity.isValid()) {
                CraftEngineFurniture.remove(entity, false, false);
            }
        }
        if (plugin.isDebugEnabled("tray")) {
            plugin.getLogger().info(I18n.formatConsole("tray.duplicates_cleaned", "pos", trayPos, "reason", reason));
        }
    }

    public void cleanupWorld(UUID worldId) {
        World world = knownWorlds.remove(worldId);
        if (world == null) {
            world = Bukkit.getWorld(worldId);
        }
        scheduledTraySyncs.removeIf(key -> key.worldId().equals(worldId));
        queuedTraySyncKeys.removeIf(key -> key.worldId().equals(worldId));
        queuedTraySyncs.removeIf(location -> location != null
                && location.getWorld() != null
                && location.getWorld().getUID().equals(worldId));
        stopQueuedSyncTaskIfIdle();
        if (world != null) {
            if (plugin.scheduler().isFolia()) {
                removeKnownOwnerTrays(worldId, "world cleanup");
            } else {
                removeAllTrays(world, "world cleanup");
            }
        }
    }

    private void removeAllTrays() {
        if (plugin.scheduler().isFolia()) {
            removeKnownOwnerTrays(null, "remove all trays");
            clearPendingSyncs();
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            removeAllTrays(world, "remove all trays");
        }
        clearPendingSyncs();
    }

    private void removeAllTrays(World world, String reason) {
        trackWorld(world);
        for (BukkitFurniture furniture : findAutoTrayFurnitures(world)) {
            Entity entity = furniture.bukkitEntity();
            if (entity == null || !entity.isValid()) {
                continue;
            }
            BlockPos trayPos = new BlockPos(
                    entity.getLocation().getBlockX(),
                    entity.getLocation().getBlockY(),
                    entity.getLocation().getBlockZ()
            );
            scheduleRemoveTrayAt(world, trayPos, reason);
        }
    }

    private void scheduleRemoveTrayAt(World world, BlockPos trayPos, String reason) {
        if (world == null || trayPos == null) {
            return;
        }

        Location trayLocation = new Location(world, trayPos.x(), trayPos.y(), trayPos.z());
        if (!plugin.scheduler().isFolia()) {
            removeTrayAt(world, trayPos, reason);
            return;
        }

        plugin.scheduler().runAt(trayLocation, () -> removeTrayAt(world, trayPos, reason));
    }

    public void cleanupAll() {
        stop();
        removeAllTrays();
    }

    private record TraySyncKey(UUID worldId, int x, int y, int z) {
    }

    private record TrayOwner(UUID worldId, BlockPos pos) {
    }
}
