package com.huidu.farmersdelight.recipe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RecipeCollections {
    private RecipeCollections() { }
    static <K, V> Map<K, V> freezeMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    static <K, J, V> Map<K, Map<J, V>> freezeNested(Map<K, Map<J, V>> values) {
        Map<K, Map<J, V>> frozen = new LinkedHashMap<>();
        values.forEach((key, children) -> frozen.put(key, freezeMap(children)));
        return freezeMap(frozen);
    }
    static <K, V> Map<K, Set<V>> freezeSets(Map<K, Set<V>> values) {
        Map<K, Set<V>> frozen = new LinkedHashMap<>();
        values.forEach((key, children) -> frozen.put(key, Set.copyOf(children)));
        return freezeMap(frozen);
    }
    static <K, J, V> Map<K, Map<J, Set<V>>> freezeNestedSets(Map<K, Map<J, Set<V>>> values) {
        Map<K, Map<J, Set<V>>> frozen = new LinkedHashMap<>();
        values.forEach((key, children) -> frozen.put(key, freezeSets(children)));
        return freezeMap(frozen);
    }
    static <K, V> Map<K, List<V>> freezeLists(Map<K, List<V>> values) {
        Map<K, List<V>> frozen = new LinkedHashMap<>();
        values.forEach((key, children) -> frozen.put(key, List.copyOf(children)));
        return freezeMap(frozen);
    }
}
