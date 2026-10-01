package com.huidu.farmersdelight.recipe;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Reuses parsed items only within one content epoch; entries remain private to their source. */
final class RecipeParseCache<R> {
    private record Key(String source, List<String> path) { }
    private record Entry<R>(Object body, R recipe) { }
    private final Map<Key, Entry<R>> entries;
    private final Map<Key, Entry<R>> next = new LinkedHashMap<>();
    private int reused;
    private int parsed;

    RecipeParseCache(RecipeParseCache<R> previous) {
        this.entries = previous == null ? Map.of() : previous.next;
    }

    R parse(String source, String path, ConfigurationSection section, Supplier<R> decoder) {
        return parse(source, List.of("recipe", path), section, decoder);
    }

    R parse(String source, List<String> path, ConfigurationSection section, Supplier<R> decoder) {
        Key key = new Key(source, List.copyOf(path));
        Object body = freeze(section);
        Entry<R> old = entries.get(key);
        if (old != null && old.body().equals(body)) {
            next.put(key, old);
            reused++;
            return old.recipe();
        }
        R recipe = decoder.get();
        if (recipe != null) next.put(key, new Entry<>(body, recipe));
        parsed++;
        return recipe;
    }

    int reused() { return reused; }
    int parsed() { return parsed; }

    private static Object freeze(Object value) {
        if (value instanceof ConfigurationSection section) return freeze(section.getValues(false));
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> copy.put(key, freeze(child)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object child : list) copy.add(freeze(child));
            return Collections.unmodifiableList(copy);
        }
        // Legacy serialized values can be mutable. Keep a defensive clone for equality comparisons.
        if (value instanceof org.bukkit.inventory.ItemStack item) return item.clone();
        return value;
    }
}
