package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.compat.KaleidoscopeCompat;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FoodGroupStore {
    public static final String FILE = "recipes/food_groups.yml";
    private static final String SOURCE = "farmersdelight:food-groups";
    private FoodGroupStore() { }

    public record Loaded(List<FoodGroupSnapshot.Group> local, FoodGroupSnapshot combined) { }

    static Loaded load(FarmersDelightPlugin plugin, List<FoodGroupSnapshot.Group> previous) {
        YamlConfiguration yaml = RecipeFileLoader.loadRecipeFile(plugin, FILE, false);
        List<FoodGroupSnapshot.Group> local = yaml == null ? previous : read(yaml);
        Map<String, List<String>> tags = new LinkedHashMap<>();
        for (var group : local) tags.put(group.id(), group.items());
        CommonTagResolver.registerSource(SOURCE, tags);
        Map<String, FoodGroupSnapshot.Group> combined = new LinkedHashMap<>();
        for (var group : local) combined.put(group.id(), group);
        for (var group : KaleidoscopeCompat.refresh(plugin)) combined.putIfAbsent(group.id(), group);
        return new Loaded(List.copyOf(local), FoodGroupSnapshot.of(List.copyOf(combined.values())));
    }

    public static List<FoodGroupSnapshot.Group> read(YamlConfiguration yaml) {
        ConfigurationSection root = yaml.getConfigurationSection("groups");
        if (root == null) return List.of();
        List<FoodGroupSnapshot.Group> groups = new ArrayList<>();
        for (var groupEntry : root.getValues(false).entrySet()) {
            String id = groupEntry.getKey();
            ConfigurationSection entry = groupEntry.getValue() instanceof ConfigurationSection value ? value : null;
            if (entry == null) throw new IllegalArgumentException("Invalid food group: " + id);
            String rawKind = entry.getString("kind", "equivalent");
            FoodGroupSnapshot.Kind kind = switch (rawKind) {
                case "equivalent" -> FoodGroupSnapshot.Kind.EQUIVALENT;
                case "seasoning" -> FoodGroupSnapshot.Kind.SEASONING;
                default -> throw new IllegalArgumentException("Unknown food group kind: " + rawKind);
            };
            groups.add(new FoodGroupSnapshot.Group(id, kind, entry.getStringList("items")));
        }
        return List.copyOf(groups);
    }

    public static void clear() { CommonTagResolver.unregisterSource(SOURCE); }
}
