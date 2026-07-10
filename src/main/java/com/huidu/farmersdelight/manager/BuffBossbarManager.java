package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders per-player buff bossbars pushed via {@link com.huidu.farmersdelight.api.buff.BuffBossbar}.
 * Two layout modes (config-driven):
 * <ul>
 *   <li>{@code stacked} — every active buff shows its own bossbar simultaneously (vanilla style).</li>
 *   <li>{@code rotating} — only one bar visible at a time, advances every
 *       {@code bossbar.rotation-interval-ticks}.</li>
 * </ul>
 *
 * <p>Lifecycle: created in {@link FarmersDelightPlugin#onEnable}; {@link #start} kicks off the
 * rotation tick (no-op in stacked mode). {@link #stop} hides every bar then clears state.
 * {@link PlayerQuitEvent} also flushes per-player bars so the map can't grow on long-running servers.
 */
public final class BuffBossbarManager implements Listener {

    public enum LayoutMode {
        STACKED, ROTATING;

        static LayoutMode parse(String s) {
            if (s == null) return STACKED;
            String norm = s.trim().toLowerCase(Locale.ROOT);
            return switch (norm) {
                case "rotating", "rotate", "cycle" -> ROTATING;
                default -> STACKED;
            };
        }
    }

    private final FarmersDelightPlugin plugin;
    private final Map<UUID, PlayerBars> players = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile LayoutMode layoutMode = LayoutMode.STACKED;
    private volatile long rotationIntervalTicks = 80L;
    private volatile PluginTask tickTask;
    private volatile long currentTick;
    private volatile boolean started;

    // Active manager for the BuffBossbar API facade: a single volatile read instead of the
    // getInstance() -> getter chain, and (unlike the plugin's non-volatile field) a safely published
    // reference for addon threads. Set in start(), identity-cleared in stop().
    private static volatile BuffBossbarManager active;

    public static BuffBossbarManager active() {
        return active;
    }

    public BuffBossbarManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /** Read {@code bossbar.enabled}, {@code bossbar.layout-mode}, {@code bossbar.rotation-interval-ticks}
     *  from the given section. Safe to call live to apply reloads — visibility is resynced for all
     *  current players. */
    public void applyConfig(ConfigurationSection section) {
        boolean wasEnabled = this.enabled;
        if (section == null) {
            this.enabled = true;
            this.layoutMode = LayoutMode.STACKED;
            this.rotationIntervalTicks = 80L;
        } else {
            this.enabled = section.getBoolean("enabled", true);
            this.layoutMode = LayoutMode.parse(section.getString("layout-mode", "stacked"));
            this.rotationIntervalTicks = Math.max(20L,
                    section.getLong("rotation-interval-ticks", 80L));
        }
        if (wasEnabled && !this.enabled) {
            hideAndClearAll();
            ensureTickTask();
            return;
        }
        for (Map.Entry<UUID, PlayerBars> entry : players.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) {
                syncVisibility(p, entry.getValue());
            }
        }
        ensureTickTask();
    }

    public void start() {
        started = true;
        active = this;
        ensureTickTask();
    }

    public void stop() {
        started = false;
        if (active == this) {
            active = null;
        }
        ensureTickTask();
        hideAndClearAll();
    }

    /**
     * The 1-tick rotation task only exists while it has work to do: started + enabled + ROTATING
     * layout. In the default STACKED mode tick() would return immediately every tick, so no task is
     * scheduled at all; a live layout-mode reload re-evaluates via the applyConfig hooks (both exit
     * paths), covering stacked-to-rotating and back. Synchronized so concurrent reloads cannot
     * double-schedule (R-CONC-002).
     */
    private synchronized void ensureTickTask() {
        boolean want = started && enabled && layoutMode == LayoutMode.ROTATING;
        if (want && tickTask == null) {
            tickTask = plugin.scheduler().runRepeating(this::tick, 1L, 1L);
        } else if (!want && tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void update(Plugin owner, Player player, NamespacedKey key,
                       Component title, float progress,
                       BossBar.Color color, BossBar.Overlay overlay) {
        if (!enabled || player == null || key == null || !player.isOnline()) return;
        float clamped = clamp(progress);
        BossBar.Color c = color == null ? BossBar.Color.WHITE : color;
        BossBar.Overlay o = overlay == null ? BossBar.Overlay.PROGRESS : overlay;
        Component t = title == null ? Component.empty() : title;

        UUID id = player.getUniqueId();
        // Synchronize on the per-player state so two addons updating the same player don't race on
        // bars.put / showBossBar ordering. Cross-player updates remain parallel.
        PlayerBars state = players.computeIfAbsent(id, k -> new PlayerBars());
        synchronized (state) {
            BossBar bar = state.bars.get(key);
            if (bar == null) {
                bar = BossBar.bossBar(t, clamped, c, o);
                state.bars.put(key, bar);
                state.snapshots.put(key, new BarSnapshot(t, clamped, c, o));
                // Only resync visibility when a new bar appears — existing bars are already attached
                // to the player (stacked) or in the rotation pool (rotating), and Adventure mutations
                // propagate to viewers automatically without a re-show packet.
                syncVisibility(player, state);
            } else {
                BarSnapshot last = state.snapshots.get(key);
                if (last != null && last.matches(t, clamped, c, o)) {
                    return;
                }
                bar.name(t);
                bar.progress(clamped);
                bar.color(c);
                bar.overlay(o);
                state.snapshots.put(key, new BarSnapshot(t, clamped, c, o));
            }
        }
    }

    public void hide(Plugin owner, Player player, NamespacedKey key) {
        if (player == null || key == null) return;
        PlayerBars state = players.get(player.getUniqueId());
        if (state == null) return;
        BossBar removed;
        boolean emptied;
        synchronized (state) {
            removed = state.bars.remove(key);
            if (removed == null) {
                // Hot path for the BAC bossbar feed: every tick it calls hide() for inactive buffs,
                // most of which were never shown for this player. Early-return before resyncing
                // anything — nothing changed, no point re-issuing showBossBar packets.
                return;
            }
            state.snapshots.remove(key);
            if (key.equals(state.currentVisible)) {
                state.currentVisible = null;
            }
            emptied = state.bars.isEmpty();
        }
        if (player.isOnline()) {
            player.hideBossBar(removed);
            if (!emptied) {
                synchronized (state) {
                    syncVisibility(player, state);
                }
            }
        }
        // NOTE: do NOT players.remove(id, state) when emptied — earlier version did, but races with a
        // concurrent update() on the same player. After this hide() exits its sync block, a concurrent
        // update() can computeIfAbsent the SAME state object and add a bar; our identity-based remove
        // then drops the (now non-empty) state, orphaning the new bar (shown to client but lost from
        // manager → never hidden). The empty PlayerBars is ~64 bytes and cleaned on PlayerQuit, so the
        // memory cost of leaving it is trivial; the rotation tick early-skips state.bars.size() <= 1.
    }

    public void hideAll(Plugin owner, Player player) {
        if (player == null) return;
        PlayerBars state = players.remove(player.getUniqueId());
        if (state == null) return;
        if (player.isOnline()) {
            synchronized (state) {
                for (BossBar bar : state.bars.values()) {
                    player.hideBossBar(bar);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PlayerBars state = players.remove(event.getPlayer().getUniqueId());
        if (state == null) return;
        Player player = event.getPlayer();
        synchronized (state) {
            for (BossBar bar : state.bars.values()) {
                player.hideBossBar(bar);
            }
        }
    }

    private void hideAndClearAll() {
        for (Iterator<Map.Entry<UUID, PlayerBars>> it = players.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PlayerBars> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            PlayerBars state = entry.getValue();
            if (player != null && player.isOnline()) {
                synchronized (state) {
                    for (BossBar bar : state.bars.values()) {
                        player.hideBossBar(bar);
                    }
                }
            }
            it.remove();
        }
    }

    private void syncVisibility(Player player, PlayerBars state) {
        if (!enabled) return;
        if (state.bars.isEmpty()) {
            state.currentVisible = null;
            return;
        }
        if (layoutMode == LayoutMode.STACKED) {
            for (BossBar bar : state.bars.values()) {
                player.showBossBar(bar);
            }
            state.currentVisible = null;
            return;
        }
        // ROTATING: show exactly one — the current pick (or the first if none yet / invalidated).
        if (state.currentVisible == null || !state.bars.containsKey(state.currentVisible)) {
            state.currentVisible = state.bars.keySet().iterator().next();
            state.lastRotationTick = currentTick;
        }
        for (Map.Entry<NamespacedKey, BossBar> entry : state.bars.entrySet()) {
            if (entry.getKey().equals(state.currentVisible)) {
                player.showBossBar(entry.getValue());
            } else {
                player.hideBossBar(entry.getValue());
            }
        }
    }

    private void tick() {
        currentTick++;
        if (!enabled || layoutMode != LayoutMode.ROTATING || players.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, PlayerBars> entry : players.entrySet()) {
            PlayerBars state = entry.getValue();
            // Single-bar players have nothing to rotate to — skip without locking.
            if (state.bars.size() <= 1) continue;
            if (currentTick - state.lastRotationTick < rotationIntervalTicks) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            synchronized (state) {
                if (state.bars.size() <= 1) continue;
                // Advance currentVisible to the next key in insertion order; wrap.
                NamespacedKey next = findNext(state);
                if (next == null) continue;
                BossBar oldBar = state.currentVisible == null ? null : state.bars.get(state.currentVisible);
                if (oldBar != null) player.hideBossBar(oldBar);
                state.currentVisible = next;
                state.lastRotationTick = currentTick;
                player.showBossBar(state.bars.get(next));
            }
        }
    }

    private static NamespacedKey findNext(PlayerBars state) {
        boolean returnNext = state.currentVisible == null;
        NamespacedKey first = null;
        for (NamespacedKey key : state.bars.keySet()) {
            if (first == null) first = key;
            if (returnNext) return key;
            if (key.equals(state.currentVisible)) returnNext = true;
        }
        // Wrapped past the end: return the first (i.e. wrap to start).
        return first;
    }

    private static float clamp(float progress) {
        if (Float.isNaN(progress) || progress < 0F) return 0F;
        if (progress > 1F) return 1F;
        return progress;
    }

    private static final class PlayerBars {
        // LinkedHashMap to preserve insertion order — rotation walks bars in the order addons created them,
        // which matches what a player would expect when reading the bossbar stack.
        final Map<NamespacedKey, BossBar> bars = new LinkedHashMap<>();
        // Snapshot of the last (title, progress, color, overlay) tuple actually pushed via the
        // BossBar setters. Lets update() return without touching Adventure when the incoming state
        // is identical — Adventure's setters compare on equals() too, but a Component.equals() walk
        // is not free and BAC's per-tick feeders re-build the same title every tick.
        final Map<NamespacedKey, BarSnapshot> snapshots = new LinkedHashMap<>();
        NamespacedKey currentVisible;
        long lastRotationTick;
    }

    private record BarSnapshot(Component title, float progress, BossBar.Color color, BossBar.Overlay overlay) {
        boolean matches(Component t, float p, BossBar.Color c, BossBar.Overlay o) {
            return Float.compare(progress, p) == 0
                    && color == c
                    && overlay == o
                    && Objects.equals(title, t);
        }
    }
}
