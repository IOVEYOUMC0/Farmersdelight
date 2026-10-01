package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.entity.Player;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

public class GuiTickManager {

    private static final long TICK_INTERVAL = 4L;
    private static GuiTickManager instance;
    private final FarmersDelightPlugin plugin;
    private record Registration(Player player, AtomicBoolean scheduled) {
    }
    private final ConcurrentHashMap<Consumer<Void>, Registration> playerTickCallbacks = new ConcurrentHashMap<>();
    private PluginTask globalTickTask;
    private volatile boolean running = false;

    private GuiTickManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public static synchronized GuiTickManager getInstance(FarmersDelightPlugin plugin) {
        if (instance == null || instance.plugin != plugin) {
            if (instance != null) {
                instance.stop();
            }
            instance = new GuiTickManager(plugin);
        }
        return instance;
    }

    public static void cleanup() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        globalTickTask = plugin.scheduler().runRepeating(() -> {
            playerTickCallbacks.forEach((callback, registration) -> {
                Player player = registration.player();
                if (!running || !registration.scheduled().compareAndSet(false, true)) {
                    return;
                }
                try {
                    plugin.scheduler().runForEntity(player, () -> {
                        try {
                            if (running && playerTickCallbacks.get(callback) == registration && player.isOnline()) {
                                callback.accept(null);
                            }
                        } catch (Exception e) {
                            // A broken GUI callback is a bug, not a recoverable condition: always surface
                            // it with the stack so it stays observable in production, not just in debug.
                            // The finally block drops the scheduled flag so the failing GUI still stops
                            // being retried this tick without aborting the loop over the other callbacks.
                            plugin.getLogger().log(Level.WARNING,
                                    "GUI tick callback failed for " + player.getName(), e);
                        } finally {
                            registration.scheduled().set(false);
                        }
                    }, () -> registration.scheduled().set(false));
                } catch (RuntimeException e) {
                    registration.scheduled().set(false);
                    playerTickCallbacks.remove(callback, registration);
                }
            });
        }, TICK_INTERVAL, TICK_INTERVAL);
    }

    public synchronized void stop() {
        running = false;
        if (globalTickTask != null) {
            globalTickTask.cancel();
            globalTickTask = null;
        }
        playerTickCallbacks.clear();
    }

    public synchronized void registerCallback(Player player, Consumer<Void> callback) {
        if (player == null) {
            return;
        }
        playerTickCallbacks.put(callback, new Registration(player, new AtomicBoolean()));
        if (!running && getActiveCallbackCount() > 0) {
            start();
        }
    }

    public synchronized void unregisterCallback(Consumer<Void> callback) {
        playerTickCallbacks.remove(callback);
        if (getActiveCallbackCount() == 0) {
            stop();
        }
    }

    public int getActiveCallbackCount() {
        return playerTickCallbacks.size();
    }
}
