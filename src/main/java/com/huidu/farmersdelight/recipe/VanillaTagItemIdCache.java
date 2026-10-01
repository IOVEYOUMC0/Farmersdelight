package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import net.momirealms.craftengine.core.util.Key;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

// Per-tag cache of CraftEngine's vanillaItemIdsByTag result so recipe matching does not re-stream the
// full vanilla tag membership on every lookup. One instance per recipe manager. Concurrent: read on
// Folia region/entity threads, cleared on reload.
final class VanillaTagItemIdCache {

    private final Function<Key, Set<String>> loader;
    private volatile Map<Key, Set<String>> cache = new ConcurrentHashMap<>();

    VanillaTagItemIdCache(FarmersDelightPlugin plugin) {
        this(key -> loadIds(plugin, key));
    }

    VanillaTagItemIdCache(Function<Key, Set<String>> loader) {
        this.loader = loader;
    }

    Set<String> getIds(Key tagKey) {
        if (tagKey == null) {
            return Set.of();
        }
        return cache.computeIfAbsent(tagKey, key -> Set.copyOf(loader.apply(key)));
    }

    private static Set<String> loadIds(FarmersDelightPlugin plugin, Key key) {
        var craftEngine = plugin.getCraftEngine();
        if (craftEngine == null || craftEngine.itemManager() == null) {
            return Set.of();
        }
        Set<String> itemIds = new HashSet<>();
        for (var itemId : craftEngine.itemManager().vanillaItemIdsByTag(key)) {
            itemIds.add(itemId.toString());
        }
        return itemIds;
    }

    void clear() {
        // In-progress lookups can finish in their retired map without blocking publication or
        // inserting old tag membership into the next content epoch.
        cache = new ConcurrentHashMap<>();
    }
}
