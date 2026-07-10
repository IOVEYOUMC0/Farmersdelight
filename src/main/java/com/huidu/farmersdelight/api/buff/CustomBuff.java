package com.huidu.farmersdelight.api.buff;

import org.bukkit.entity.Player;

/**
 * A server-side custom buff an addon tracks outside of vanilla {@code PotionEffect}s — typical examples
 * are FarmersDelight's Comfort / Nourishment and Brewin' And Chewin's Tipsy / Sweet Heart / Raging /
 * Intoxication, which keep their own per-player state maps that vanilla {@code milk_bucket} can't see.
 *
 * <p>Register implementations through {@link CustomBuffRegistry#register} during your plugin's
 * {@code onEnable}. FarmersDelight's central milk-consume listener will then route:
 * <ul>
 *   <li>{@code minecraft:milk_bucket} → remove every active registered buff from the drinker
 *       (vanilla "milk wipes all" semantics, extended to custom state);</li>
 *   <li>{@code farmersdelight:milk_bottle} → remove exactly one active buff, preferring entries with
 *       {@link #isLowPriority()} returning {@code false} so the original mod's
 *       {@code brewinandchewin:low_priority/milk_bottle} tag semantics carry over.</li>
 * </ul>
 *
 * <p>Implementations must be idempotent: {@link #remove(Player)} on a player without the buff is a
 * no-op, and {@link #isActive(Player)} returns {@code false} both before activation and after removal.
 */
public interface CustomBuff {

    /** Stable id used for de-duplication in the registry and (if you want) logging. */
    String id();

    /** True iff {@code player} currently has this buff active. */
    boolean isActive(Player player);

    /** Drop the buff from {@code player}. Must be safe to call when the buff isn't currently active
     *  and from the player's region thread; the FD listener invokes this from
     *  {@code PlayerItemConsumeEvent}, which runs on the consuming player's thread. */
    void remove(Player player);

    /** Grant this buff to {@code player} at {@code level} (1-based, mirroring {@link #level(Player)}:
     *  {@code 1} = baseline / amp 0) for {@code durationSeconds}. Powers admin grant tooling (the
     *  {@code /fd buff give} command) so every registered buff can be handed out through the registry
     *  without the command knowing each addon's apply API. Return {@code true} when the buff was applied,
     *  {@code false} when this buff can't be granted programmatically (the default) so callers can report
     *  it. Implementations run on the target player's region thread. */
    default boolean apply(Player player, int level, int durationSeconds) {
        return false;
    }

    /** When a single-buff cleanser (milk_bottle) picks a buff to remove and any non-low-priority buff
     *  is currently active, low-priority ones are skipped. Default {@code false}; flip to {@code true}
     *  for "ambient drunk meter"-style buffs (Tipsy) so the bottle prefers to cancel one of the harder
     *  effects first, matching the original {@code low_priority/milk_bottle} effect tag from BAC. */
    default boolean isLowPriority() {
        return false;
    }

    /** Current effective level for {@code player} — 1-based ({@code 1} = baseline / amp 0,
     *  {@code 2} = amp 1, …), or {@code 0} when inactive. Powers HUD placeholders that show
     *  the buff's current strength. Default: returns {@code 1} when {@link #isActive(Player)} is true,
     *  {@code 0} otherwise. */
    default int level(Player player) {
        return isActive(player) ? 1 : 0;
    }

    /** Seconds the buff has left on {@code player}, or {@code 0} when inactive. Powers HUD
     *  placeholders that show the buff's remaining duration. Default: {@code 0} (unknown / not
     *  reported). */
    default int remainingSeconds(Player player) {
        return 0;
    }

    /** Translatable lang key for the buff's display name (e.g. {@code "buff.farmersdelight.comfort"}).
     *  Used by HUD placeholders that print the buff name. Default: empty string (the placeholder will
     *  fall back to the buff's {@link #id()}). */
    default String nameKey() {
        return "";
    }

    /** Persist this buff's live state onto {@code player} so it survives relog / restart — and, on a
     *  network that syncs the whole player PDC (HuskSync / MySQLPlayerDataBridge etc.), carries across
     *  servers. FarmersDelight's central persistence lifecycle calls this on quit while the buff's
     *  in-memory state is still intact. Store under your own namespaced PDC keys.
     *
     *  <p>Default no-op — implement only for buffs that should survive a relog; genuinely ephemeral
     *  effects can stay in-memory. An inactive buff should remove its keys so no stale entry lingers. */
    default void saveState(Player player) {
    }

    /** Restore this buff's state onto {@code player} from wherever {@link #saveState(Player)} wrote it.
     *  FarmersDelight calls this on join and once more after a short (configurable) delay, so that
     *  whole-profile sync plugins which apply the synced PDC a moment after join are still caught.
     *
     *  <p>MUST be gap-filling: if the buff is already active (from the immediate restore, or a dose the
     *  player gained since joining), leave it untouched — otherwise the retry would clobber live state.
     *  Default no-op. */
    default void restoreState(Player player) {
    }
}
