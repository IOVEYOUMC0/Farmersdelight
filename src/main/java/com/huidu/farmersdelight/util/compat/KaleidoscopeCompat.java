package com.huidu.farmersdelight.util.compat;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.recipe.FoodGroupSnapshot;
import com.huidu.farmersdelight.recipe.FuzzyRecipeSpec;
import com.huidu.farmersdelight.util.CommonTagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Optional integration through public registries and recorded-recipe item data. */
public final class KaleidoscopeCompat {
    private static final String SOURCE = "farmersdelight:compat/kaleidoscope";
    private static final NamespacedKey MARKER = new NamespacedKey("kaleidoscopecookeryplugin", "has_recipe");
    private static final NamespacedKey INGREDIENTS = new NamespacedKey("kaleidoscopecookeryplugin", "recipe_ingredients");
    private static final int MAX_RECORDED_INGREDIENTS = 9;
    private static volatile boolean recipeBooks;
    private static Object knives;
    private static Method unregisterKnife;
    private static final Set<String> contributedKnives = new LinkedHashSet<>();
    private static volatile Set<String> importedKnives = Set.of();

    private KaleidoscopeCompat() { }

    public static boolean isRecipeBook(ItemStack stack) {
        return recipeBooks && stack != null && !stack.getType().isAir() && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE);
    }

    public static List<String> ingredients(ItemStack stack) {
        if (!isRecipeBook(stack)) return List.of();
        return parseIngredients(stack.getItemMeta().getPersistentDataContainer().get(INGREDIENTS, PersistentDataType.STRING));
    }

    public static List<String> parseIngredients(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 4096) return List.of();
        String[] values = encoded.split(",", MAX_RECORDED_INGREDIENTS + 1);
        if (values.length > MAX_RECORDED_INGREDIENTS) return List.of();
        List<String> parsed = new ArrayList<>(values.length);
        try {
            for (String value : values) parsed.add(FuzzyRecipeSpec.normalizeId(value));
        } catch (IllegalArgumentException invalid) {
            return List.of();
        }
        return List.copyOf(parsed);
    }

    public static boolean isImportedKnife(String id) { return id != null && importedKnives.contains(id); }

    /** Called during recipe publication, never from a cooking tick. */
    public static synchronized List<FoodGroupSnapshot.Group> refresh(FarmersDelightPlugin plugin) {
        clearRegistrations();
        Plugin other = Bukkit.getPluginManager().getPlugin("KaleidoscopeCookeryPlugin");
        boolean enabled = other != null && other.isEnabled()
                && plugin.getConfigBoolean(true, "compatibility.kaleidoscope.enabled");
        recipeBooks = enabled && plugin.getConfigBoolean(true, "compatibility.kaleidoscope.recipe-auto-fill");
        if (!enabled) return List.of();
        List<FoodGroupSnapshot.Group> groups = new ArrayList<>();
        try {
            ClassLoader loader = other.getClass().getClassLoader();
            Class<?> api = Class.forName("net.kaleidoscope.cookery.api.KaleidoscopeCookeryAPI", true, loader);
            Object tags = api.getMethod("itemTags").invoke(null);
            Method members = tags.getClass().getMethod("members", Class.forName("net.momirealms.craftengine.core.util.Key", false, loader));
            Map<String, List<String>> imported = new LinkedHashMap<>();
            for (Object key : (Collection<?>) tags.getClass().getMethod("keys").invoke(tags)) {
                imported.put(key.toString(), normalizedMembers((Collection<?>) members.invoke(tags, key), true));
            }
            if (plugin.getConfigBoolean(true, "compatibility.kaleidoscope.item-tags")) {
                CommonTagResolver.registerSource(SOURCE, imported);
            }
            if (plugin.getConfigBoolean(true, "compatibility.kaleidoscope.food-groups")) {
                Class<?> type = Class.forName("net.kaleidoscope.cookery.recipe.FoodGroups", true, loader);
                Object registry = type.getMethod("instance").invoke(null);
                readGroups(groups, registry, type.getMethod("equivalentTags"), imported, FoodGroupSnapshot.Kind.EQUIVALENT);
                readGroups(groups, registry, type.getMethod("seasoningTags"), imported, FoodGroupSnapshot.Kind.SEASONING);
            }
            if (plugin.getConfigBoolean(true, "compatibility.kaleidoscope.knives")) {
                knives = api.getMethod("choppingBoardKnives").invoke(null);
                Method register = knives.getClass().getMethod("register", String.class);
                unregisterKnife = knives.getClass().getMethod("unregister", String.class);
                Set<String> ids = new LinkedHashSet<>();
                for (Object id : (Collection<?>) knives.getClass().getMethod("registeredIds").invoke(knives)) {
                    try { ids.add(FuzzyRecipeSpec.normalizeId(id.toString())); }
                    catch (IllegalArgumentException ignored) { }
                }
                for (String tag : plugin.getConfigStringList("compatibility.kaleidoscope.knife-tags")) {
                    ids.addAll(expandTag(tag.replaceFirst("^#", ""), imported, new LinkedHashSet<>(), 0));
                }
                importedKnives = Set.copyOf(ids);
                for (String id : plugin.getKnifeItemIds()) {
                    if (Boolean.TRUE.equals(register.invoke(knives, id))) contributedKnives.add(id);
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Kaleidoscope registry integration could not be refreshed", error);
        }
        return List.copyOf(groups);
    }

    private static void readGroups(List<FoodGroupSnapshot.Group> destination, Object registry, Method list,
                                   Map<String, List<String>> tags, FoodGroupSnapshot.Kind kind) throws ReflectiveOperationException {
        for (Object key : (Collection<?>) list.invoke(registry)) {
            List<String> items = List.copyOf(expandTag(key.toString(), tags, new LinkedHashSet<>(), 0));
            if (!items.isEmpty()) destination.add(new FoodGroupSnapshot.Group(key.toString(), kind, items));
        }
    }

    private static Set<String> expandTag(String tag, Map<String, List<String>> tags, Set<String> path, int depth) {
        if (depth >= 8 || !path.add(tag)) return Set.of();
        Set<String> items = new LinkedHashSet<>();
        for (String member : tags.getOrDefault(tag, List.of())) {
            if (member.startsWith("#")) items.addAll(expandTag(member.substring(1), tags, path, depth + 1));
            else items.add(member);
        }
        path.remove(tag);
        return items;
    }

    private static List<String> normalizedMembers(Collection<?> members, boolean allowTags) {
        List<String> values = new ArrayList<>();
        for (Object entry : members) {
            String id = entry.toString();
            try {
                values.add(id.startsWith("#") && allowTags ? "#" + FuzzyRecipeSpec.normalizeId(id.substring(1)) : FuzzyRecipeSpec.normalizeId(id));
            } catch (IllegalArgumentException ignored) { }
        }
        return List.copyOf(values);
    }

    private static void clearRegistrations() {
        CommonTagResolver.unregisterSource(SOURCE);
        if (knives != null && unregisterKnife != null) {
            for (String id : contributedKnives) {
                try { unregisterKnife.invoke(knives, id); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        }
        contributedKnives.clear();
        importedKnives = Set.of();
        knives = null;
        unregisterKnife = null;
    }

    public static synchronized void shutdown() { recipeBooks = false; clearRegistrations(); }
}
