package com.huidu.farmersdelight.api.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Extension point for {@code /fd debugtools}. An addon (e.g. Brewin' And Chewin') implements this
 * interface to plug a new <em>target</em> (like {@code keg}) into the existing command. FarmersDelight
 * itself only ships when built with {@code -PdebugTools=true}; the registry is otherwise dormant and
 * the extension's {@link #place}/{@link #activate}/{@link #status} are simply never invoked.
 *
 * <p>Register an instance once at plugin enable via
 * {@link DebugToolRegistry#register(DebugToolExtension)}. The {@link #name()} becomes the target
 * keyword in {@code /fd debugtools place <name> ...}.
 */
@ApiStatus.OverrideOnly
public interface DebugToolExtension {

    /** Lower-case target keyword (e.g. {@code "keg"}). Used both for command parsing and tab-complete. */
    String name();

    /**
     * Mass-place this extension's block in a grid starting at {@code origin}. The exact placement
     * pattern is the extension's choice; FD's built-in implementations use {@code grid = ceil(sqrt(count))}
     * with {@code spacing} between cells and {@code layers} stacked vertically.
     *
     * <p>Call {@code undo.capture(loc)} BEFORE mutating each target block so the placement can be
     * reverted by {@code /fd debugtools undo}. Captures that don't end up changing state are silently
     * no-op'd on undo.
     *
     * @return how many blocks actually went into the world (≤ count × layers).
     */
    int place(Player player, Location origin, int count, int spacing, int layers, UndoSink undo);

    /** Callback the extension uses to record pre-state into FD's debug-tool undo batch. */
    @FunctionalInterface
    interface UndoSink {
        /** Snapshot the block state at {@code loc} so a later {@code /fd debugtools undo} can restore it. */
        void capture(Location loc);
    }

    /**
     * Optional: fill placed blocks with sample state so they actually start ticking / fermenting / etc.
     * Default: no-op. Return the number of blocks transitioned to an "active" state.
     */
    default int activate(Player player) {
        return 0;
    }

    /**
     * Optional: status lines shown after the built-in TickManager snapshot in
     * {@code /fd debugtools status}. Return an empty list to omit. Each entry is one line.
     */
    default List<String> status(Player player) {
        return List.of();
    }

    /**
     * Optional: called by FD during {@code /fd debugtools undo} on every restored location, BEFORE the
     * block data is reverted. Use this to release in-memory tracking and remove block-entity NBT
     * belonging to this extension so an undone placement doesn't leak ghost state. Implementations
     * should no-op cheaply when the location isn't one of their blocks.
     */
    default void cleanupBeforeUndo(Location location) {
    }
}
