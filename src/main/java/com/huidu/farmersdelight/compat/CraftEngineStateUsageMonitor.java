package com.huidu.farmersdelight.compat;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import net.momirealms.craftengine.bukkit.block.BukkitBlockManager;
import net.momirealms.craftengine.core.block.BlockManager;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.pack.allocator.IdAllocator;
import net.momirealms.craftengine.core.plugin.config.Config;

import java.util.HashMap;
import java.util.Map;

public final class CraftEngineStateUsageMonitor {
    private static final String FARMERS_DELIGHT_NAMESPACE = "farmersdelight:";
    private static final int LOW_FREE_STATE_WARNING_THRESHOLD = 32;

    private CraftEngineStateUsageMonitor() {
    }

    public static void logRealStateUsage(FarmersDelightPlugin plugin, String reason) {
        try {
            BukkitBlockManager blockManager = plugin.getCraftEngine().blockManager();
            if (blockManager == null) {
                return;
            }

            Usage usage = inspect(blockManager);
            plugin.getLogger().info(String.format(
                    "CraftEngine server-side block state usage%s: %d/%d used, %d free, %d FarmersDelight states.",
                    reason == null || reason.isBlank() ? "" : " after " + reason,
                    usage.used(),
                    usage.total(),
                    usage.free(),
                    usage.farmersDelightStates()
            ));

            if (usage.free() == 0) {
                plugin.getLogger().warning("CraftEngine server-side block states are exhausted. If FarmersDelight blocks cannot be placed, this is a CraftEngine block-state capacity issue, not a FarmersDelight placement bug. Increase CraftEngine's server-side block state limit or remove unused custom blocks/cache entries.");
            } else if (usage.free() <= LOW_FREE_STATE_WARNING_THRESHOLD) {
                plugin.getLogger().warning("CraftEngine server-side block states are almost full (" + usage.free() + " free). New or reloaded custom blocks may fail to register/place once the pool is exhausted.");
            }
        } catch (Throwable throwable) {
            plugin.getLogger().fine("Failed to inspect CraftEngine block state usage: " + throwable.getMessage());
        }
    }

    private static Usage inspect(BukkitBlockManager blockManager) {
        int total = Config.serverSideBlocks();
        int vanillaOffset = blockManager.vanillaBlockStateCount();
        IdAllocator allocator = blockManager.internalIdAllocator();
        Map<String, Integer> cachedIds = allocator.cachedIdMap();
        Map<Integer, String> cachedOwners = new HashMap<>(cachedIds.size());
        for (Map.Entry<String, Integer> entry : cachedIds.entrySet()) {
            cachedOwners.put(entry.getValue(), entry.getKey());
        }

        int used = 0;
        int farmersDelightStates = 0;
        for (int i = 0; i < total; i++) {
            ImmutableBlockState state = blockManager.getImmutableBlockStateUnsafe(i + vanillaOffset);
            if (state != null && !state.isEmpty()) {
                used++;
                if (state.toString().startsWith(FARMERS_DELIGHT_NAMESPACE)) {
                    farmersDelightStates++;
                }
                continue;
            }

            String cachedOwner = cachedOwners.get(i);
            if (cachedOwner != null) {
                used++;
                if (cachedOwner.startsWith(FARMERS_DELIGHT_NAMESPACE)) {
                    farmersDelightStates++;
                }
            }
        }

        return new Usage(total, used, Math.max(0, total - used), farmersDelightStates);
    }

    private record Usage(int total, int used, int free, int farmersDelightStates) {
    }
}
