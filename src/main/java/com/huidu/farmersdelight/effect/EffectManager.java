package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.GameRule;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores and updates custom nourishment state for online players.
 */
public final class EffectManager {

    private static final int EFFECT_FADE_WARNING_TICKS = 200;
    private static final Map<UUID, Integer> nourishmentDurations = new ConcurrentHashMap<>();

    private EffectManager() {
    }

    /**
     * Applies or refreshes nourishment.
     *
     * @param player target player
     * @param durationSeconds duration in seconds
     */
    public static void applyNourishment(Player player, int durationSeconds) {
        UUID playerId = player.getUniqueId();
        int currentDuration = nourishmentDurations.getOrDefault(playerId, 0);
        int newDuration = Math.max(currentDuration, durationSeconds * 20);
        nourishmentDurations.put(playerId, newDuration);
        if (currentDuration <= 0) {
            player.sendMessage(I18n.formatNamed(
                    "effects.nourishment.start",
                    player,
                    durationPlaceholders(newDuration)
            ));
        }
        EffectListener.trackPlayer(player.getUniqueId());
    }

    /**
     * Returns whether the player currently has nourishment.
     */
    public static boolean hasNourishment(Player player) {
        return getNourishmentDuration(player) > 0;
    }

    /**
     * Removes nourishment from the player.
     */
    public static void removeNourishment(Player player) {
        nourishmentDurations.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.sendMessage(I18n.get("effects.nourishment.end", player));
        }
        checkAndUntrack(player);
    }

    /**
     * Stops tracking players that no longer have active custom effects.
     */
    private static void checkAndUntrack(Player player) {
        if (!hasNourishment(player)) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    /**
     * Updates remaining duration and applies the in-game nourishment behavior.
     */
    public static void tick(Player player) {
        if (player == null || !player.isValid() || !player.isOnline() || player.isDead()) {
            return;
        }

        try {
            UUID playerId = player.getUniqueId();
            int nourishmentDuration = nourishmentDurations.getOrDefault(playerId, 0);

            if (nourishmentDuration <= 0) {
                EffectListener.untrackPlayer(playerId);
                return;
            }

            tickNourishment(player);
            if (player.isValid()) {
                if (nourishmentDuration == EFFECT_FADE_WARNING_TICKS) {
                    player.sendMessage(I18n.formatNamed(
                            "effects.nourishment.fade",
                            player,
                            durationPlaceholders(nourishmentDuration)
                    ));
                }
                int newDuration = nourishmentDuration - 1;
                if (newDuration > 0) {
                    nourishmentDurations.put(playerId, newDuration);
                } else {
                    player.sendMessage(I18n.get("effects.nourishment.end", player));
                    nourishmentDurations.remove(playerId);
                }
            }

            if (nourishmentDuration <= 1) {
                EffectListener.untrackPlayer(playerId);
            }
        } catch (Exception e) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    public static void clearPlayer(Player player) {
        if (player != null) {
            nourishmentDurations.remove(player.getUniqueId());
        }
    }

    public static void clearAll() {
        nourishmentDurations.clear();
    }

    private static int getNourishmentDuration(Player player) {
        if (player == null) {
            return 0;
        }
        return nourishmentDurations.getOrDefault(player.getUniqueId(), 0);
    }

    /**
     * Nourishment reduces exhaustion unless hunger-based natural regeneration is active.
     */
    private static void tickNourishment(Player player) {
        if (player.isDead()) {
            return;
        }

        var maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr == null) {
            return;
        }

        boolean naturalRegen = Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRule.NATURAL_REGENERATION));
        boolean isHurt = player.getHealth() < maxHealthAttr.getValue();
        boolean isPlayerHealingWithSaturation = naturalRegen && isHurt && player.getSaturation() > 0;

        if (!isPlayerHealingWithSaturation) {
            player.setExhaustion(0);
        }
    }

    private static Map<String, String> durationPlaceholders(int durationTicks) {
        int seconds = Math.max(0, durationTicks / 20);
        int minutes = seconds / 60;
        int remainSeconds = seconds % 60;
        return Map.of(
                "seconds", String.valueOf(seconds),
                "minutes", String.valueOf(minutes),
                "time", minutes > 0
                        ? minutes + "m " + remainSeconds + "s"
                        : seconds + "s"
        );
    }
}

