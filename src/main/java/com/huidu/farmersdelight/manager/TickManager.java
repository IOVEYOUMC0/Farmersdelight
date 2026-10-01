package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntityController;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.UUID;

public class TickManager {

    private final FarmersDelightPlugin plugin;
    private final CookingPotEffectManager effectManager;
    private final PerformanceMonitor performanceMonitor;
    private PluginTask tickTask;
    private PluginTask cleanupTask;
    private volatile boolean running = false;
    
    private final ActiveWorkRegistry<ActiveBlock, ChunkKey, UUID> activeWork =
            new ActiveWorkRegistry<>(TickManager::chunkOf, ChunkKey::worldId);
    private static final int MARK_DRAIN_BUDGET = 1024;
    private final Map<ChunkKey, Object> scheduledChunks = new ConcurrentHashMap<>();
    private final Map<ActiveBlock, CookingPotBlockEntityController> nativePots = new ConcurrentHashMap<>();
    private final AtomicInteger nativeAwakePots = new AtomicInteger();
    private long observedRecipeGeneration = Long.MIN_VALUE;
    private final LongAdder submittedChunkTasks = new LongAdder();
    private final LongAdder coalescedChunkTasks = new LongAdder();

    private final Map<ActiveBlock, Long> lastProcessedTicks = new ConcurrentHashMap<>();
    private final Map<ActiveBlock, Long> progressDisplayLastUpdateTicks = new ConcurrentHashMap<>();
    // Heat source checks (two CE state fetches: isHeatSource + isConductor) are cached per block and
    // only refreshed every HEAT_SOURCE_CHECK_INTERVAL_TICKS; a block-below heat source rarely changes.
    private final Map<ActiveBlock, Long> heatSourceLastCheckTicks = new ConcurrentHashMap<>();
    private static final int HEAT_SOURCE_CHECK_INTERVAL_TICKS = 10;
    private volatile int activeCookingPotCount;
    private boolean activeBlockLimitWarningShown;
    private int cleanupCursor;
    // Reload-written on the reload/command thread, read by the global tick thread — volatile for a
    // happens-before edge (Folia keeps reload on a different thread than the tick).
    private volatile int cookingPotTickBudget = 512;
    private volatile int cookingPotProgressDisplayUpdateIntervalTicks = 8;
    private volatile int cookingPotProgressDisplayDisableAboveActivePots = 512;
    private volatile int activeBlockWarningThreshold = 1000;
    private final AtomicLong foliaTickClock = new AtomicLong();

    private static final int TICK_INTERVAL = 4;
    private static final int DEFAULT_ACTIVE_BLOCK_WARNING_THRESHOLD = 1000;
    private static final int CLEANUP_INTERVAL = 6000;
    // Bounds how many region tasks one cleanup run submits; see scheduleCookingPotCleanup.
    private static final int CLEANUP_DISPATCH_BUDGET = 128;
    private static final int DEFAULT_COOKING_POT_TICK_BUDGET = 512;
    // Max catch-up ticks applied in a single processing pass. Large enough that cooking pots starved by the tick budget
    // (active blocks far exceeding the budget) don't lose real elapsed cooking time. This does not "cook a huge batch on reload":
    // tickCookingPot uses Math.min(duration, ...) to cap progress at the recipe duration, and finishes cooking at most once per
    // pass (a single if, not a loop), producing only one batch regardless of catch-up size; previousTick is also reset on
    // (re)activation and refreshed every time the pot is selected, so catch-up never includes unloaded time.
    // The cap still keeps a sane bound to avoid int overflow at elapsedTicks*2 in the cooldown branch.
    private static final int MAX_ELAPSED_TICKS = 72_000;
    public TickManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.effectManager = new CookingPotEffectManager(plugin);
        this.performanceMonitor = new PerformanceMonitor(plugin);
        reloadConfig();
    }

    public void reloadConfig() {
        effectManager.reloadConfig();
        cookingPotTickBudget = Math.max(1, plugin.getConfigInt(DEFAULT_COOKING_POT_TICK_BUDGET,
                "cooking-pot.tick-budget",
                "performance.cooking-pot-tick-budget"));
        cookingPotProgressDisplayUpdateIntervalTicks = Math.max(1,
                plugin.getCookingPotProgressDisplayUpdateIntervalTicks());
        cookingPotProgressDisplayDisableAboveActivePots = Math.max(0,
                plugin.getCookingPotProgressDisplayDisableAboveActivePots());
        activeBlockWarningThreshold = Math.max(1, plugin.getConfigInt(DEFAULT_ACTIVE_BLOCK_WARNING_THRESHOLD,
                "performance.warnings.active-block-threshold",
                "performance.max-active-blocks-warning"));
        performanceMonitor.reloadConfig();
        wakeAllNativePots();
    }

    public void start() {
        if (running) return;
        running = true;
        
        tickTask = plugin.scheduler().runRepeating(this::tick, 1L, TICK_INTERVAL);
        cleanupTask = plugin.scheduler().runRepeating(this::performCleanup, CLEANUP_INTERVAL, CLEANUP_INTERVAL);
        I18n.logDetail("startup", "tick.started", "interval", TICK_INTERVAL);
    }

    public void stop() {
        running = false;
        performanceMonitor.stop();
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        
        activeWork.clear();
        nativePots.clear();
        nativeAwakePots.set(0);
        activeCookingPotCount = 0;
        lastProcessedTicks.clear();
        progressDisplayLastUpdateTicks.clear();
        heatSourceLastCheckTicks.clear();
        scheduledChunks.clear();
        effectManager.cleanup();

        I18n.logDetail("startup", "tick.stopped");
    }
    
    private void performCleanup() {
        performanceMonitor.checkPooledWarnings();
        performanceMonitor.pruneOldWarnings();
        if (plugin.scheduler().isFolia()) {
            scheduleCookingPotCleanup();
            return;
        }

        int cleanedCount = sweepInvalidCookingPotBlockEntities();

        if (cleanedCount > 0) {
            I18n.logInfo("tick.cleanup_completed", "count", cleanedCount);
        }
    }

    // The tracked block-entity set can hold thousands of entries, and submitting all of them in one tick spikes
    // the region scheduler. Each run covers CLEANUP_DISPATCH_BUDGET entries from a rotating cursor, so every
    // entry is still reached across successive runs (a stale entry merely survives one more interval).
    private void scheduleCookingPotCleanup() {
        List<Location> locations = CookingPotBlockBehavior.getBlockEntityLocations();
        int size = locations.size();
        if (size == 0) {
            cleanupCursor = 0;
            return;
        }
        int budget = Math.min(CLEANUP_DISPATCH_BUDGET, size);
        int start = cleanupCursor >= size ? 0 : cleanupCursor;

        for (int processed = 0; processed < budget; processed++) {
            Location location = locations.get((start + processed) % size);
            if (location == null || location.getWorld() == null) {
                continue;
            }
            try {
                plugin.scheduler().runAt(location, () -> cleanupCookingPotBlockEntity(
                        location.getWorld(),
                        new BlockPosKey(location)
                ));
            } catch (RuntimeException ignored) {
            }
        }

        cleanupCursor = (start + Math.max(1, budget)) % size;
    }

    /**
     * Bounded sweep of the tracked cooking pots for the single-threaded (Paper) path.
     *
     * <p>Every entry costs a chunk-residency check plus a CraftEngine block-state read, so sweeping the whole
     * tracked set in one pass put "worlds x tracked pots" of work on the main thread in a single tick — the
     * same spike the Folia path already avoids by dispatching a bounded batch. Below the budget the sweep is
     * the whole set, as before. Above it, a snapshot is rotated through so that every entry is still reached
     * across successive runs, while any one run costs at most {@link #CLEANUP_DISPATCH_BUDGET} entries.
     */
    private int sweepInvalidCookingPotBlockEntities() {
        int total = 0;
        List<World> worlds = Bukkit.getWorlds();
        for (World world : worlds) {
            if (world != null) {
                total += CookingPotBlockBehavior.getBlockEntityCount(world);
            }
        }
        if (total == 0) {
            cleanupCursor = 0;
            return 0;
        }

        if (total <= CLEANUP_DISPATCH_BUDGET) {
            int cleaned = 0;
            for (World world : worlds) {
                if (world == null) {
                    continue;
                }
                cleaned += sweepInvalidCookingPotBlockEntities(world);
            }
            cleanupCursor = 0;
            return cleaned;
        }

        List<PendingCleanup> pending = new ArrayList<>(total);
        for (World world : worlds) {
            if (world == null) {
                continue;
            }
            for (Map.Entry<BlockPosKey, CookingPotBlockEntity> entry : CookingPotBlockBehavior.getBlockEntityEntries(world)) {
                pending.add(new PendingCleanup(world, entry.getKey()));
            }
        }

        int size = pending.size();
        SweepWindow window = SweepWindow.of(size, cleanupCursor, CLEANUP_DISPATCH_BUDGET);
        cleanupCursor = window.nextCursor();
        if (window.count() == 0) {
            return 0;
        }

        int cleaned = 0;
        for (int processed = 0; processed < window.count(); processed++) {
            PendingCleanup entry = pending.get(window.indexAt(processed));
            try {
                if (cleanupInvalidCookingPotBlockEntity(entry.world(), entry.posKey())) {
                    cleaned++;
                }
            } catch (Throwable t) {
                // The CE state lookup reads the chunk and can run other plugins' listeners; their failure
                // leaves this entry tracked for a later run instead of aborting the sweep.
                performanceMonitor.warnCleanupFailure(entry.world(), entry.posKey(), t);
            }
        }

        return cleaned;
    }

    private int sweepInvalidCookingPotBlockEntities(World world) {
        int cleaned = 0;
        for (Map.Entry<BlockPosKey, CookingPotBlockEntity> entry : CookingPotBlockBehavior.getBlockEntityEntries(world)) {
            BlockPosKey posKey = entry.getKey();
            try {
                if (cleanupInvalidCookingPotBlockEntity(world, posKey)) {
                    cleaned++;
                }
            } catch (Throwable t) {
                // The CE state lookup reads the chunk and can run other plugins' listeners; their failure
                // leaves this entry tracked for a later run instead of aborting the sweep.
                performanceMonitor.warnCleanupFailure(world, posKey, t);
            }
        }
        return cleaned;
    }

    /** One tracked pot captured for the rotating cleanup sweep. */
    private record PendingCleanup(World world, BlockPosKey posKey) {
    }

    private void cleanupCookingPotBlockEntity(World world, BlockPosKey posKey) {
        try {
            cleanupInvalidCookingPotBlockEntity(world, posKey);
        } catch (Throwable t) {
            performanceMonitor.warnCleanupFailure(world, posKey, t);
        }
    }

    private boolean cleanupInvalidCookingPotBlockEntity(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return false;
        }

        // hasCookingPotBehavior reads the CraftEngine state through the chunk, and a read of a chunk
        // that is not resident loads it synchronously: the load fires that chunk's entity-load events,
        // which is work cleanup has no business starting (a listener that throws there would fail this
        // task, and the load itself is a stall). Cleanup is a background chore, so a non-resident chunk
        // is skipped and revisited by a later run.
        if (!world.isChunkLoaded(posKey.x() >> 4, posKey.z() >> 4)) {
            return false;
        }

        if (!CookingPotBlockBehavior.hasCookingPotBehavior(world, posKey)) {
            CookingPotBlockBehavior.removeBlockEntity(world, posKey);
            return true;
        }
        return false;
    }
    
    public void markActive(World world, BlockPosKey posKey, BlockType type) {
        if (world == null || posKey == null || type == null) return;
        ActiveBlock key = new ActiveBlock(world.getUID(), world, posKey, type);
        CookingPotBlockEntityController nativePot = nativePots.get(key);
        if (nativePot != null) nativePot.requestTickWake();
        else activeWork.submit(key, true);
    }

    public void markInactive(World world, BlockPosKey posKey, BlockType type) {
        if (world == null || posKey == null || type == null) return;
        ActiveBlock key = new ActiveBlock(world.getUID(), world, posKey, type);
        // Native pots decide when to sleep after applying any remaining progress decay.
        if (!nativePots.containsKey(key)) activeWork.submit(key, false);
    }

    public void registerNativePot(World world, BlockPosKey pos, CookingPotBlockEntityController controller) {
        if (world == null) return;
        ActiveBlock key = new ActiveBlock(world.getUID(), world, pos, BlockType.COOKING_POT);
        CookingPotBlockEntityController previous = nativePots.put(key, controller);
        if (previous != controller) {
            if (previous != null && !previous.isTickSleeping()) nativeAwakePots.decrementAndGet();
            if (!controller.isTickSleeping()) nativeAwakePots.incrementAndGet();
        }
        activeWork.submit(key, false);
        heatSourceLastCheckTicks.remove(key);
    }

    public void unregisterNativePot(World world, BlockPosKey pos, CookingPotBlockEntityController controller) {
        if (world == null) return;
        ActiveBlock key = new ActiveBlock(world.getUID(), world, pos, BlockType.COOKING_POT);
        if (nativePots.remove(key, controller)) {
            if (!controller.isTickSleeping()) nativeAwakePots.decrementAndGet();
            heatSourceLastCheckTicks.remove(key);
            progressDisplayLastUpdateTicks.remove(key);
            lastProcessedTicks.remove(key);
        }
    }

    public boolean isNativePotRegistered(World world, BlockPosKey pos, CookingPotBlockEntityController controller) {
        return world != null && nativePots.get(new ActiveBlock(world.getUID(), world, pos, BlockType.COOKING_POT)) == controller;
    }

    public void nativePotStateChanged(World world, BlockPosKey pos, CookingPotBlockEntityController controller, boolean sleeping) {
        if (isNativePotRegistered(world, pos, controller)) nativeAwakePots.addAndGet(sleeping ? -1 : 1);
    }

    public void wakeNativePot(World world, BlockPosKey pos) {
        if (world == null || pos == null) return;
        ActiveBlock key = new ActiveBlock(world.getUID(), world, pos, BlockType.COOKING_POT);
        CookingPotBlockEntityController controller = nativePots.get(key);
        if (controller != null) {
            heatSourceLastCheckTicks.remove(key);
            controller.requestTickWake();
        }
    }

    /** A heat source can reach a pot directly, or through one conducting block. */
    public void wakePotsAbove(World world, BlockPosKey changed) {
        if (nativePots.isEmpty()) return;
        wakeNativePot(world, changed);
        wakeNativePot(world, new BlockPosKey(changed.x(), changed.y() + 1, changed.z()));
        wakeNativePot(world, new BlockPosKey(changed.x(), changed.y() + 2, changed.z()));
    }

    public void wakeAllNativePots() {
        nativePots.forEach((key, controller) -> {
            heatSourceLastCheckTicks.remove(key);
            controller.requestTickWake();
        });
    }

    /** Called only by CraftEngine's synchronous ticker on the owning block thread. */
    public boolean tickNativePot(World world, BlockPosKey pos, CookingPotBlockEntityController controller) {
        if (!running) return true;
        if (!isNativePotRegistered(world, pos, controller)) return false;
        ActiveBlock key = new ActiveBlock(world.getUID(), world, pos, BlockType.COOKING_POT);
        PerformanceMonitor.Session profile = performanceMonitor.recording();
        PerformanceMonitor.Timing timing = profile == null ? null : profile.timings.get(PerformanceMonitor.Feature.COOKING_POT);
        long started = timing == null ? 0L : System.nanoTime();
        try {
            return tickCookingPot(key, world, pos, TICK_INTERVAL, Bukkit.getCurrentTick());
        } catch (Exception failure) {
            performanceMonitor.warnFeatureFailure("native-cooking-pot", "Error ticking cooking pot at " + pos, world, failure);
            return true;
        } finally {
            if (timing != null) {
                long cost = System.nanoTime() - started;
                timing.record(cost);
                profile.recordBlockCost(world.getUID(), pos, cost);
            }
        }
    }

    public record NativeTickerSnapshot(int registered, int awake, int sleeping, long sleeps, long wakes) { }

    public NativeTickerSnapshot nativeTickerSnapshot() {
        int sleeping = 0;
        long sleeps = 0, wakes = 0;
        for (CookingPotBlockEntityController controller : nativePots.values()) {
            if (controller.isTickSleeping()) sleeping++;
            sleeps += controller.tickSleepCount();
            wakes += controller.tickWakeCount();
        }
        int size = nativePots.size();
        return new NativeTickerSnapshot(size, Math.max(0, size - sleeping), sleeping, sleeps, wakes);
    }

    /**
     * Queues removal of every tracked block in one unloading chunk.
     *
     * <p>The chunk-unload cleanup drops the block entities and CraftEngine's per-chunk index, but nothing
     * told this manager, so the entries stayed in the active registry plus its three per-block maps. That is
     * the same defect {@link #cleanupWorld(UUID)} documents one level up: the tick loop's chunk guard
     * ({@code !world.isChunkLoaded}) returns early, so nothing ever unregistered them, and the entries
     * accumulated for every chunk a player visited that held a station. Removing them here is safe because a
     * chunk reload always re-registers: {@code CookingPotBlockBehavior} and the pot controller both call
     * {@link #markActive} when the entity is (re)hydrated, and the controller's cached-chunk path
     * ({@code getChunkAtIfLoaded}) marks active without waiting for {@code loadCustomData}.
     *
     * <p>Only the tick bookkeeping is dropped; the cooked progress lives in the block entity, which the
     * chunk-unload save pass has already snapshotted into the controller.
     */
    public void markInactiveInChunk(World world, int chunkX, int chunkZ) {
        if (world == null) {
            return;
        }
        ChunkKey chunk = new ChunkKey(world.getUID(), chunkX, chunkZ);
        activeWork.retireGroup(chunk);
        scheduledChunks.remove(chunk);
        nativePots.forEach((key, controller) -> {
            if (chunkOf(key).equals(chunk)) unregisterNativePot(world, key.posKey(), controller);
        });
    }

    /**
     * Queues removal of every tracked block in a world that is unloading. ActiveBlock holds a strong
     * World reference, and the per-behaviour cleanupWorld methods drop their own maps directly rather
     * than routing through markInactive, so without this the entries stay forever: the World is retained
     * and the tick loop's idle fast path can never fire again because the set is never empty.
     */
    public void cleanupWorld(UUID worldId) {
        if (worldId == null) {
            return;
        }
        effectManager.cleanupWorld(worldId);
        activeWork.retireWorld(worldId);
        scheduledChunks.keySet().removeIf(chunk -> chunk.worldId().equals(worldId));
        nativePots.forEach((key, controller) -> {
            if (key.worldId().equals(worldId)) unregisterNativePot(key.world(), key.posKey(), controller);
        });
    }

    /** Drops the effect budget/viewer context of one unloading chunk; the map is otherwise never cleared. */
    public void cleanupEffectChunk(World world, int blockX, int blockZ) {
        if (world == null) {
            return;
        }
        effectManager.cleanupChunk(world.getUID(), ManagerSupport.chunkKey(blockX >> 4, blockZ >> 4));
    }

    private void tick() {
        if (!running) return;
        if (plugin.getCookingPotRecipes() != null) {
            long generation = plugin.getCookingPotRecipes().recipeGeneration();
            if (generation != observedRecipeGeneration) {
                observedRecipeGeneration = generation;
                wakeAllNativePots();
            }
        }
        if (activeWork.isIdle() && !performanceMonitor.isRecording()) return;
        PerformanceMonitor.Session profile = performanceMonitor.recording();
        long startedNanos = profile == null ? 0L : System.nanoTime();
        int size = 0;
        int processed = 0;
        try {
            long currentTick = advanceCurrentTick();
            for (ActiveWorkRegistry.Change<ActiveBlock> change : activeWork.drain(MARK_DRAIN_BUDGET)) {
                if (change.reset() || !change.active()) {
                    lastProcessedTicks.remove(change.key());
                    progressDisplayLastUpdateTicks.remove(change.key());
                    heatSourceLastCheckTicks.remove(change.key());
                }
                if (change.active()) lastProcessedTicks.putIfAbsent(change.key(), currentTick);
            }
            size = activeWork.counts().active();
            activeCookingPotCount = size + Math.max(0, nativeAwakePots.get());
            if (size == 0) return;
            if (size > activeBlockWarningThreshold) {
                if (!activeBlockLimitWarningShown) {
                    activeBlockLimitWarningShown = true;
                    plugin.getLogger().warning(I18n.formatNamedArgs("console.performance.active_blocks_exceeded",
                            "threshold", activeBlockWarningThreshold, "count", size));
                }
            } else {
                activeBlockLimitWarningShown = false;
            }

            List<ActiveWorkRegistry.Selection<ActiveBlock>> selected = activeWork.select(cookingPotTickBudget);
            processed = selected.size();
            if (plugin.scheduler().isFolia()) dispatchChunkBatches(selected);
            else for (var selection : selected) {
                processActiveBlockInRegion(selection, selection.key().world(), currentTick);
            }
        } finally {
            if (profile != null) profile.recordPass(System.nanoTime() - startedNanos, size, processed);
        }
    }

    public long startPerformanceProfile(PerformanceMonitor.Feature feature) {
        return performanceMonitor.start(feature);
    }

    public PerformanceSnapshot finishPerformanceProfile(long id) {
        PerformanceMonitor.Snapshot snapshot = performanceMonitor.finish(id);
        return snapshot == null ? null : getPerformanceSnapshot(snapshot);
    }

    PerformanceMonitor.Timing featureTiming(PerformanceMonitor.Feature feature) {
        return performanceMonitor.timing(feature);
    }

    /**
     * Throttled report of a failure raised while ticking one block, shared with the skillet and stove
     * managers: their tick tasks run on the same cadence and need the same per-world throttle.
     */
    void warnFeatureFailure(String feature, String message, World world, Throwable failure) {
        performanceMonitor.warnFeatureFailure(feature, message, world, failure);
    }

    public PerformanceSnapshot getPerformanceSnapshot() {
        return getPerformanceSnapshot(performanceMonitor.snapshot());
    }

    private PerformanceSnapshot getPerformanceSnapshot(PerformanceMonitor.Snapshot profile) {
        ActiveWorkRegistry.Counts counts = activeWork.counts();
        int nativeAwake = Math.max(0, nativeAwakePots.get());
        return new PerformanceSnapshot(
                profile.pass().calls(),
                profile.pass().totalNanos(),
                profile.pass().lastNanos(),
                profile.pass().maxNanos(),
                profile.lastActiveBlocks(),
                profile.lastProcessedBlocks(),
                counts.active() + nativeAwake,
                counts.active() + nativeAwake,
                counts.additions(),
                counts.removals(),
                cookingPotTickBudget,
                TICK_INTERVAL,
                profile.active(),
                profile.pass().historyNanos(),
                profile.blockNanos(),
                profile.features(),
                profile.elapsedNanos(),
                profile.omittedHotspotCalls()
        );
    }

    public record DispatchSnapshot(long submittedChunkTasks, long coalescedChunkTasks, int pendingChunkTasks) { }

    public DispatchSnapshot dispatchSnapshot() {
        return new DispatchSnapshot(submittedChunkTasks.sum(), coalescedChunkTasks.sum(), scheduledChunks.size());
    }

    private static ChunkKey chunkOf(ActiveBlock block) {
        return new ChunkKey(block.worldId(), block.posKey().x() >> 4, block.posKey().z() >> 4);
    }

    private void dispatchChunkBatches(List<ActiveWorkRegistry.Selection<ActiveBlock>> selected) {
        Map<ChunkKey, List<ActiveWorkRegistry.Selection<ActiveBlock>>> batches = new LinkedHashMap<>();
        for (var selection : selected) {
            if (activeWork.isCurrent(selection)) {
                batches.computeIfAbsent(chunkOf(selection.key()), ignored -> new ArrayList<>()).add(selection);
            }
        }
        for (var batch : batches.entrySet()) {
            ChunkKey chunk = batch.getKey();
            Object token = new Object();
            if (scheduledChunks.putIfAbsent(chunk, token) != null) {
                coalescedChunkTasks.increment();
                continue;
            }
            World world = batch.getValue().getFirst().key().world();
            try {
                plugin.scheduler().runAt(world, chunk.x(), chunk.z(), () -> {
                    try {
                        long tick = getCurrentTick();
                        for (var selection : batch.getValue()) processActiveBlockInRegion(selection, world, tick);
                    } finally {
                        scheduledChunks.remove(chunk, token);
                    }
                });
                submittedChunkTasks.increment();
            } catch (RuntimeException rejected) {
                scheduledChunks.remove(chunk, token);
            }
        }
    }

    private void processActiveBlockInRegion(ActiveWorkRegistry.Selection<ActiveBlock> selection, World world,
                                            long currentTick) {
        if (!running || !activeWork.isCurrent(selection)) return;
        ActiveBlock activeBlock = selection.key();
        if (nativePots.containsKey(activeBlock)) return;

        BlockPosKey posKey = activeBlock.posKey();
        if (!world.isChunkLoaded(posKey.x() >> 4, posKey.z() >> 4)) {
            return;
        }

        try {
            if (Objects.requireNonNull(activeBlock.type()) == BlockType.COOKING_POT) {
                PerformanceMonitor.Session profile = performanceMonitor.recording();
                PerformanceMonitor.Timing timing = profile == null ? null
                        : profile.timings.get(PerformanceMonitor.Feature.COOKING_POT);
                long started = timing == null ? 0L : System.nanoTime();
                try {
                    tickCookingPot(activeBlock, world, posKey, consumeElapsedTicks(activeBlock, currentTick), currentTick);
                } finally {
                    if (timing != null) {
                        long cost = System.nanoTime() - started;
                        timing.record(cost);
                        profile.recordBlockCost(activeBlock.worldId(), posKey, cost);
                    }
                }
            } else {
                throw new IllegalArgumentException("Unexpected value: " + activeBlock.type());
            }
        } catch (Exception e) {
            // Throttled per world and type: this runs every TICK_INTERVAL ticks, so an unthrottled report
            // would print one line per affected block per pass.
            performanceMonitor.warnFeatureFailure("tick-" + activeBlock.type(), I18n.formatNamedArgs(
                    "console.tick.error_ticking",
                    "type", activeBlock.type(),
                    "pos", posKey,
                    "error", e.getMessage()), world, e);
        }
    }

    private int consumeElapsedTicks(ActiveBlock activeBlock, long currentTick) {
        Long previousTick = lastProcessedTicks.put(activeBlock, currentTick);
        if (previousTick == null) {
            return TICK_INTERVAL;
        }
        long elapsed = currentTick - previousTick;
        if (elapsed <= 0L) {
            return TICK_INTERVAL;
        }
        return (int) Math.min(MAX_ELAPSED_TICKS, elapsed);
    }

    private long getCurrentTick() {
        if (plugin.scheduler().isFolia()) {
            return foliaTickClock.get();
        }
        return Bukkit.getCurrentTick();
    }

    private long advanceCurrentTick() {
        if (plugin.scheduler().isFolia()) {
            return foliaTickClock.addAndGet(TICK_INTERVAL);
        }
        return Bukkit.getCurrentTick();
    }

    private boolean tickCookingPot(ActiveBlock activeBlock, World world, BlockPosKey posKey,
                                int elapsedTicks, long currentTick) {
        Block block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);

        if (state == null || state.isEmpty()) {
            // A /ce reload unbinds custom states for its parse window, making them unresolvable while the
            // injected server block is still in the world. Skip the tick and keep everything registered:
            // deleting here would destroy a live pot's contents mid-reload.
            if (CraftEngineBlocks.isCustomBlock(block)) {
                return true;
            }
            unregisterCookingPotBlock(activeBlock, world, posKey);
            // Keep the CE-side stored NBT: only the break/removal callbacks delete data. A block replaced
            // behind CE's back (WorldEdit /setblock) just leaves inert leftover NBT behind.
            CookingPotBlockBehavior.removeBlockEntity(world, posKey, false);
            return false;
        }

        // Resolve the behavior once from the already-fetched state; a null result also answers "no longer a
        // cooking pot", so this replaces a second getBlockAt + CE custom-state fetch per pot per tick.
        CookingPotBlockBehavior behavior = CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class);
        if (behavior == null) {
            unregisterCookingPotBlock(activeBlock, world, posKey);
            // The block is no longer a cooking pot (replaced by a different block that bypassed the CE break
            // callbacks); retire its floating progress display + recipe-name cache instead of leaving them to
            // linger until the periodic sweep.
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            return false;
        }
        
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(world, posKey);
        if (entity == null) {
            unregisterCookingPotBlock(activeBlock, world, posKey);
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            return false;
        }

        // Advance buffer -> output during the pot tick instead of relying on GUI refresh or manual pickup.
        entity.tryMovePendingToOutput();

        // An empty pot can still be mid-cook: the player (or a hopper) can strip every ingredient while the
        // progress is running, and the mod regresses its cookTime in that state. Keep the pot registered until
        // the progress has decayed to 0, otherwise the bar freezes at its last value forever.
        if (!entity.hasStoredContents() && entity.getCookingProgress() <= 0) {
            unregisterCookingPotBlock(activeBlock, world, posKey);
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            return false;
        }

        boolean hasHeat;

        Long lastHeatCheck = heatSourceLastCheckTicks.get(activeBlock);
        long currentTickForHeat = currentTick;
        if (lastHeatCheck != null && currentTickForHeat - lastHeatCheck < HEAT_SOURCE_CHECK_INTERVAL_TICKS) {
            hasHeat = entity.hasHeatSource();
        } else {
            // Resolved only on a cache miss: world.getBlockAt builds a Location per call, and at the default
            // 10-tick interval against a 4-tick pass the cached branch answers two passes out of three.
            Block blockBelow = world.getBlockAt(posKey.x(), posKey.y() - 1, posKey.z());
            // Pre-fetch the CE state of blockBelow once, then share it across isHeatSource
            // and isConductor to avoid two independent CraftEngineBlocks.getCustomBlockState()
            // calls on the same block.
            ImmutableBlockState belowState = CraftEngineBlocks.getCustomBlockState(blockBelow);
            hasHeat = plugin.getHeatSourceConfig().isHeatSource(blockBelow, belowState);
            if (!hasHeat && plugin.getHeatSourceConfig().isConductor(blockBelow, belowState)) {
                Block blockTwoBelow = world.getBlockAt(posKey.x(), posKey.y() - 2, posKey.z());
                hasHeat = plugin.getHeatSourceConfig().isHeatSource(blockTwoBelow);
            }
            heatSourceLastCheckTicks.put(activeBlock, currentTickForHeat);
        }

        entity.setHasHeatSource(hasHeat);
        effectManager.emit(world, posKey, entity, hasHeat, behavior, currentTickForHeat);

        boolean canCook = hasHeat && entity.canCook();
        CookingPotRecipe recipe = canCook ? entity.getCurrentRecipe() : null;
        if (plugin.isDebugEnabled("cooking_pot")) {
            plugin.getLogger().info(I18n.formatNamedArgs("console.debug.cooking_pot_tick",
                    "pos", posKey,
                    "has_heat", hasHeat,
                    "can_cook", canCook,
                    "recipe", recipe != null ? recipe.getId() : "null",
                    "progress", entity.getCookingProgress(),
                    "duration", entity.getCookingDuration(),
                    "inputs", entity.debugInputSummary()));
        }

        if (recipe != null) {
            // Restarts the bar when the matched recipe is a different dish than the one the progress was
            // accumulated for (see beginCookingRecipe: the id survives the passes with no matching recipe).
            entity.beginCookingRecipe(recipe.getId());
            entity.setCookingDuration(recipe.getCookTime());

            int newProgress = CookingPotBlockEntity.advanceOrDecayProgress(
                    entity.getCookingProgress(), entity.getCookingDuration(), elapsedTicks, true);
            entity.setCookingProgress(newProgress);
            if (newProgress >= entity.getCookingDuration()) {
                Location blockLoc = ManagerSupport.toLocation(world, posKey);
                if (blockLoc == null) {
                    unregisterCookingPotBlock(activeBlock, world, posKey);
                    return false;
                }
                if (entity.finishCooking(world, blockLoc)) {
                    // Keep the pot active after a successful cook so remaining ingredients can immediately
                    // start the next batch.
                    recipe = entity.getCurrentRecipe();
                }
            }
        } else if (entity.getCookingProgress() > 0) {
            entity.setCookingProgress(CookingPotBlockEntity.advanceOrDecayProgress(
                    entity.getCookingProgress(), entity.getCookingDuration(), elapsedTicks, false));
        }

        if (entity.getCookingProgress() > 0 && recipe != null) {
            if (shouldSuppressCookingPotProgressDisplay()) {
                progressDisplayLastUpdateTicks.remove(activeBlock);
                CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            } else if (shouldUpdateCookingPotProgressDisplay(activeBlock, currentTick)) {
                // Populate the recipe-name cache the progress display reads, but only when the
                // show-recipe-name option is enabled so it stays empty (and allocation-free) otherwise.
                if (plugin.isShowRecipeNameInProgressDisplay()) {
                    CookingPotBlockBehavior.setCookingRecipeItem(world, posKey, recipe.getResult());
                }
                CookingPotBlockBehavior.updateProgressDisplay(world, posKey, entity.getProgressPercent());
            }
        } else {
            progressDisplayLastUpdateTicks.remove(activeBlock);
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
        }
        return entity.getCookingProgress() > 0 || (hasHeat && entity.canCook());
    }

    private void unregisterCookingPotBlock(ActiveBlock activeBlock, World world, BlockPosKey posKey) {
        progressDisplayLastUpdateTicks.remove(activeBlock);
        heatSourceLastCheckTicks.remove(activeBlock);
        markInactive(world, posKey, BlockType.COOKING_POT);
    }

    private boolean shouldSuppressCookingPotProgressDisplay() {
        return cookingPotProgressDisplayDisableAboveActivePots > 0
                && activeCookingPotCount > cookingPotProgressDisplayDisableAboveActivePots;
    }

    private boolean shouldUpdateCookingPotProgressDisplay(ActiveBlock activeBlock, long currentTick) {
        if (cookingPotProgressDisplayUpdateIntervalTicks <= TICK_INTERVAL) {
            return true;
        }

        Long lastUpdateTick = progressDisplayLastUpdateTicks.get(activeBlock);
        if (lastUpdateTick != null
                && currentTick - lastUpdateTick < cookingPotProgressDisplayUpdateIntervalTicks) {
            return false;
        }
        progressDisplayLastUpdateTicks.put(activeBlock, currentTick);
        return true;
    }

    public record PerformanceSnapshot(
            long samples,
            long totalNanos,
            long lastNanos,
            long maxNanos,
            long lastActiveBlocks,
            long lastProcessedBlocks,
            int currentActiveBlocks,
            int snapshotActiveBlocks,
            int pendingAdditions,
            int pendingRemovals,
            int tickBudget,
            int tickInterval,
            boolean statsEnabled,
            long[] historyNanos,
            Map<PerformanceMonitor.Hotspot, Long> blockNanos,
            Map<PerformanceMonitor.Feature, PerformanceMonitor.TimingSnapshot> features,
            long elapsedNanos,
            long omittedHotspotCalls
    ) {
        public double averageNanos() {
            return samples <= 0L ? 0.0D : (double) totalNanos / samples;
        }
    }

    public enum BlockType {
        COOKING_POT
    }
    
    private record ChunkKey(UUID worldId, int x, int z) { }

    private record ActiveBlock(UUID worldId, World world, BlockPosKey posKey, BlockType type) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ActiveBlock that = (ActiveBlock) o;
            return worldId.equals(that.worldId) && posKey.equals(that.posKey) && type == that.type;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * worldId.hashCode() + posKey.hashCode()) + type.hashCode();
        }
    }
}
