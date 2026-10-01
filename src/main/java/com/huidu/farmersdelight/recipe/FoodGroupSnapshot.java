package com.huidu.farmersdelight.recipe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detached group data. Region threads read this snapshot without calling another plugin. */
public record FoodGroupSnapshot(List<Group> groups, Map<String, String> equivalents, Set<String> seasonings) {
    public enum Kind { EQUIVALENT, SEASONING }

    public record Group(String id, Kind kind, List<String> items) {
        public Group {
            id = FuzzyRecipeSpec.normalizeId(id);
            if (kind == null || items == null || items.isEmpty() || items.size() > 4096) {
                throw new IllegalArgumentException("A food group needs a kind and 1 to 4096 members");
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String item : items) normalized.add(FuzzyRecipeSpec.normalizeId(item));
            items = List.copyOf(normalized);
        }
    }

    public static FoodGroupSnapshot of(List<Group> groups) {
        Map<String, String> equivalents = new LinkedHashMap<>();
        Set<String> seasonings = new LinkedHashSet<>();
        for (Group group : groups) {
            if (group.kind() == Kind.SEASONING) seasonings.addAll(group.items());
            else for (String item : group.items()) equivalents.putIfAbsent(item, "#" + group.id());
        }
        return new FoodGroupSnapshot(List.copyOf(groups), Collections.unmodifiableMap(equivalents), Set.copyOf(seasonings));
    }

    public String canonical(String item) { return equivalents.getOrDefault(item, item); }
    public static FoodGroupSnapshot empty() { return of(List.of()); }
}
