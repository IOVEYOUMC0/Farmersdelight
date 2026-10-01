package com.huidu.farmersdelight.recipe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ideal ingredient ratios for an optional fuzzy cooking-pot recipe. */
public record FuzzyRecipeSpec(Map<String, Integer> perfect, boolean useEquivalentFoods,
                              boolean useSeasonings, double minimumScore) {
    public FuzzyRecipeSpec {
        if (perfect == null || perfect.isEmpty() || perfect.size() > 54) {
            throw new IllegalArgumentException("A fuzzy recipe needs 1 to 54 ingredient types");
        }
        Map<String, Integer> checked = new LinkedHashMap<>();
        perfect.forEach((id, weight) -> {
            String normalized = normalizeId(id);
            if (weight == null || weight < 1 || weight > 64) {
                throw new IllegalArgumentException("Ideal ingredient weights must be between 1 and 64");
            }
            if (checked.putIfAbsent(normalized, weight) != null) {
                throw new IllegalArgumentException("Duplicate ideal ingredient: " + normalized);
            }
        });
        perfect = Collections.unmodifiableMap(checked);
        if (!Double.isFinite(minimumScore) || minimumScore < 0 || minimumScore > 1) {
            throw new IllegalArgumentException("minimum-score must be between 0 and 1");
        }
    }

    public static String normalizeId(String id) {
        if (id == null) throw new IllegalArgumentException("Item id is required");
        String value = id.trim();
        if (value.startsWith("craftengine:")) value = value.substring(12);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || value.equals("minecraft:air")) {
            throw new IllegalArgumentException("Invalid ingredient item id: " + id);
        }
        return value;
    }
}
