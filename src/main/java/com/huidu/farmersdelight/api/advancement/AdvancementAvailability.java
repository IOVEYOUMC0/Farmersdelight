package com.huidu.farmersdelight.api.advancement;

import java.util.Locale;

/**
 * Why the advancement system is or is not running, as evaluated by Farmersdelight-Plugin-Pro.
 *
 * <p>Addons report this instead of repeating the individual checks, so a tab that was never registered
 * names its actual cause. The conditions are ordered: the first one that fails decides the result.
 */
public enum AdvancementAvailability {

    /** The advancement system is running. */
    AVAILABLE,

    /** Farmersdelight-Plugin-Pro is not loaded yet, or is shutting down. */
    PLUGIN_UNAVAILABLE,

    /** Farmersdelight-Plugin-Pro's configuration disables advancements. */
    DISABLED_BY_CONFIG,

    /** UltimateAdvancementAPI is missing or disabled. */
    API_NOT_INSTALLED,

    /** UltimateAdvancementAPI is installed but lacks the advancement layout this plugin registers through. */
    API_PATCH_MISSING;

    /**
     * Stable identifier for log messages, e.g. {@code api_patch_missing}. Addons pass this as their
     * {@code {reason}} placeholder; they do not switch over the constants, so a new cause needs no change
     * on their side and no translation per addon.
     */
    public String logName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
