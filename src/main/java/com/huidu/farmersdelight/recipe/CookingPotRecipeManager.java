package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.api.recipe.IngredientMatching;
import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.pack.PackSections;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.MMOItemsCompat;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import com.huidu.farmersdelight.api.recipe.AddonRecipeFiles;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.util.CommonTagResolver;

public class CookingPotRecipeManager {

    private final FarmersDelightPlugin plugin;
    private static final int MAX_CACHE_SIZE = 2048;
    private final VanillaTagItemIdCache vanillaItemIdsByTagCache;
    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile YamlConfiguration lastFileDocument;
    private volatile RecipeParseCache<CookingPotRecipe> parsedRecipes;

    record Snapshot(Map<String, CookingPotRecipe> recipes,
                    Map<String, Map<String, CookingPotRecipe>> customRecipes,
                    Map<String, Set<String>> ingredientToRecipes,
                    Map<String, Map<String, Set<String>>> customIngredientToRecipes,
                    List<CookingPotRecipe> sortedRecipes,
                    Map<String, List<CookingPotRecipe>> sortedCustomRecipes,
                    Map<String, List<CookingPotRecipe>> sortedCustomOnlyRecipes,
                    Map<String, List<CookingPotRecipe>> resultToRecipes,
                    Set<String> validContainerKeys, int packRecipeCount, long generation,
                    Map<String, CookingPotRecipe> recipeCache, Set<String> recipeMisses) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                    Map.of(), Set.of(), 0, 0, newMatchCache(), newMissCache());
        }
    }

    private static Map<String, CookingPotRecipe> newMatchCache() {
        return Collections.synchronizedMap(new LinkedHashMap<>(MAX_CACHE_SIZE + 1, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CookingPotRecipe> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        });
    }

    private static Set<String> newMissCache() {
        return Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(MAX_CACHE_SIZE + 1, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        });
    }

    public long recipeGeneration() { return snapshot.generation(); }

    public record ParseMetrics(int parsed, int reused) { }
    public ParseMetrics parseMetrics() {
        RecipeParseCache<CookingPotRecipe> current = parsedRecipes;
        return current == null ? new ParseMetrics(0, 0) : new ParseMetrics(current.parsed(), current.reused());
    }

    // Recipes registered at runtime by addons via the public API. Kept separate so they survive a
    // /fd reload (which rebuilds the file-backed maps); merged into the published maps in loadRecipes().
    private final Map<String, CookingPotRecipe> externalRecipes = new ConcurrentHashMap<>();
    // Republishing after an external (un)register is coalesced to the next tick, so registering a batch
    // of addon recipes triggers a single loadRecipes() instead of one full file reload per recipe.
    private final java.util.concurrent.atomic.AtomicBoolean externalRepublishScheduled = new java.util.concurrent.atomic.AtomicBoolean();

    public CookingPotRecipeManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.vanillaItemIdsByTagCache = new VanillaTagItemIdCache(plugin);
    }

    public void loadRecipes() {
        loadRecipes(RecipeFileLoader.loadRecipeFile(plugin, "recipes/cooking_pot_recipes.yml"), false);
    }

    public void loadRecipesIncrementally() {
        loadRecipes(RecipeFileLoader.loadRecipeFile(plugin, "recipes/cooking_pot_recipes.yml"), true);
    }

    private synchronized void loadRecipes(YamlConfiguration config, boolean incremental) {
        if (config == null) return;
        RecipeParseCache<CookingPotRecipe> parsing = new RecipeParseCache<>(incremental ? parsedRecipes : null);
        // Build everything into fresh local collections first, then publish atomically (below), so readers
        // never see a half-cleared map. Do not clear()/refill the live fields in place.
        Map<String, CookingPotRecipe> newRecipes = new LinkedHashMap<>();
        Map<String, Map<String, CookingPotRecipe>> newCustomRecipes = new HashMap<>();
        Map<String, Set<String>> newIngredientToRecipes = new HashMap<>();
        Map<String, Map<String, Set<String>>> newCustomIngredientToRecipes = new HashMap<>();
        Set<String> newValidContainerKeys = new HashSet<>();
        // Recipes that got their container from their own result instead of the file; reported below so an
        // operator can see which entries rely on the inference.
        int[] inferredContainers = {0};
        // Ids whose winning definition came from a CraftEngine pack section; the registry buckets in the
        // startup summary read this, so it may only count entries that survived the merges below.
        Set<String> packIds = new HashSet<>();

        Set<String> overriddenExternalIds = externalOverrideIds(config, "cooking_pot");
        RecipeFileLoader.loadRecipeSections(plugin, config, "cooking_pot_recipes", "cooking pot",
                "recipes/cooking_pot_recipes.yml", (recipeId, section) -> {
                    CookingPotRecipe recipe = parsing.parse("file", recipeId, section, () -> parseRecipe(recipeId, section, 6));
                    newRecipes.put(recipeId, recipe);

                    indexDefaultRecipe(newIngredientToRecipes, recipeId, recipe);
                    indexContainer(newValidContainerKeys, recipe);
                    if (section.get("container") == null && recipe.getContainer() != null) {
                        inferredContainers[0]++;
                    }
                });
        loadCustomRecipes(config, newCustomRecipes, newCustomIngredientToRecipes, newValidContainerKeys, inferredContainers, parsing, "file");

        // Recipes a CraftEngine pack declares under cooking_recipes. Loaded after the plugin's own file so a
        // pack can never silently replace a built-in recipe, and before the API merge below so an explicit
        // runtime registration still wins on an id clash. CraftEngine read the files; see PackSections.
        for (PackSections.Section packSection : plugin.packSectionsOf(PackSection.COOKING_POT)) {
            RecipeFileLoader.loadRecipeSections(plugin, packSection.yaml(), PackSection.COOKING_POT.rootKey(),
                    "cooking pot [" + packSection.source() + "]",
                    packSection.source(),
                    (recipeId, section) -> {
                        if (newRecipes.containsKey(recipeId)) {
                            I18n.logWarning("recipe.pack_duplicate_skipped", "id", recipeId, "source", packSection.source());
                            return;
                        }
                        CookingPotRecipe recipe = parsing.parse(packSection.source(), recipeId, section, () -> parseRecipe(recipeId, section, 6));
                        newRecipes.put(recipeId, recipe);
                        packIds.add(recipeId);
                        indexDefaultRecipe(newIngredientToRecipes, recipeId, recipe);
                        indexContainer(newValidContainerKeys, recipe);
                        if (section.get("container") == null && recipe.getContainer() != null) {
                            inferredContainers[0]++;
                        }
                    });
            loadCustomRecipes(packSection.yaml(), newCustomRecipes, newCustomIngredientToRecipes, newValidContainerKeys, inferredContainers, parsing, packSection.source());
        }

        // Merge addon-registered recipes last so they survive reloads; an editor override is explicit and wins.
        for (CookingPotRecipe recipe : externalRecipes.values()) {
            if (!overriddenExternalIds.contains(recipe.getId()) || !newRecipes.containsKey(recipe.getId())
                    || AddonRecipeFiles.ownerOf("cooking_pot", recipe.getId()) != null) {
                newRecipes.put(recipe.getId(), recipe);
                packIds.remove(recipe.getId());
                indexDefaultRecipe(newIngredientToRecipes, recipe.getId(), recipe);
                indexContainer(newValidContainerKeys, recipe);
            }
        }
        int newPackRecipeCount = packIds.size();

        List<CookingPotRecipe> newSortedRecipes = sortedRecipeList(newRecipes);
        // Only reported when it actually happens: a pack that declares every container keeps the boot log quiet.
        if (inferredContainers[0] > 0) {
            I18n.logDetail("recipe", "recipe.cooking_pot_inferred_containers", "count", inferredContainers[0]);
        }
        Map<String, List<CookingPotRecipe>> newSortedCustomRecipes = new HashMap<>();
        Map<String, List<CookingPotRecipe>> newSortedCustomOnlyRecipes = new HashMap<>();
        for (Map.Entry<String, Map<String, CookingPotRecipe>> entry : newCustomRecipes.entrySet()) {
            newSortedCustomOnlyRecipes.put(entry.getKey(), sortedRecipeList(entry.getValue()));
            Map<String, CookingPotRecipe> merged = new LinkedHashMap<>(newRecipes);
            merged.putAll(entry.getValue());
            newSortedCustomRecipes.put(entry.getKey(), sortedRecipeList(merged));
        }

        Map<String, List<CookingPotRecipe>> newResultToRecipes = buildResultIndex(newSortedRecipes);
        for (List<CookingPotRecipe> groupRecipes : newSortedCustomOnlyRecipes.values()) {
            for (CookingPotRecipe recipe : groupRecipes) {
                newResultToRecipes.computeIfAbsent(getItemKey(recipe.getResult()), k -> new ArrayList<>(1)).add(recipe);
            }
        }

        Snapshot next = new Snapshot(RecipeCollections.freezeMap(newRecipes),
                RecipeCollections.freezeNested(newCustomRecipes), RecipeCollections.freezeSets(newIngredientToRecipes),
                RecipeCollections.freezeNestedSets(newCustomIngredientToRecipes), List.copyOf(newSortedRecipes),
                RecipeCollections.freezeLists(newSortedCustomRecipes), RecipeCollections.freezeLists(newSortedCustomOnlyRecipes),
                RecipeCollections.freezeLists(newResultToRecipes), Set.copyOf(newValidContainerKeys), newPackRecipeCount,
                snapshot.generation() + 1, newMatchCache(), newMissCache());
        lastFileDocument = config;
        parsedRecipes = parsing;
        vanillaItemIdsByTagCache.clear();
        snapshot = next;
        // Invalidate the recipe-list GUI display cache: this republish path (incl. addon register/
        // unregister) bypasses RecipeViewGui.clearConfigCache.
        RecipeViewGui.clearRecipeDisplayCache();
        // The decoded-snapshot cache is keyed by strings owned by the recipes being replaced, so it is
        // dropped with them rather than being left to hold entries no recipe references any more.
        RecipeItemCodec.clearDecodeCache();
    }

    private void loadCustomRecipes(YamlConfiguration config,
                                   Map<String, Map<String, CookingPotRecipe>> targetCustomRecipes,
                                   Map<String, Map<String, Set<String>>> targetCustomIndex,
                                   Set<String> targetContainerKeys,
                                   int[] inferredContainers, RecipeParseCache<CookingPotRecipe> parsing, String source) {
        ConfigurationSection root = config.getConfigurationSection("custom_cooking_pot_recipes");
        if (root == null) {
            return;
        }

        int loadedCount = 0;
        for (String groupId : root.getKeys(false)) {
            ConfigurationSection groupSection = root.getConfigurationSection(groupId);
            if (groupSection == null) {
                continue;
            }
            Map<String, CookingPotRecipe> groupRecipes = targetCustomRecipes.computeIfAbsent(groupId, key -> new LinkedHashMap<>());
            for (String recipeId : groupSection.getKeys(false)) {
                ConfigurationSection section = groupSection.getConfigurationSection(recipeId);
                if (section == null) {
                    continue;
                }
                // A recipe already present in this group was contributed by the plugin's own file or an
                // earlier pack; a later pack must not replace it silently.
                if (groupRecipes.containsKey(recipeId)) {
                    I18n.logWarning("recipe.pack_duplicate_skipped", "id", groupId + "." + recipeId, "source", "custom cooking pot group");
                    continue;
                }
                try {
                    CookingPotRecipe recipe = parsing.parse(source, List.of("custom", groupId, recipeId), section,
                            () -> parseRecipe(recipeId, section, 54));
                    groupRecipes.put(recipeId, recipe);
                    indexCustomRecipe(targetCustomIndex, groupId, recipeId, recipe);
                    indexContainer(targetContainerKeys, recipe);
                    if (section.get("container") == null && recipe.getContainer() != null) {
                        inferredContainers[0]++;
                    }
                    loadedCount++;
                } catch (Exception e) {
                    I18n.logWarning("recipe.custom_cooking_pot_load_failed",
                            "id", groupId + "." + recipeId,
                            "path", groupSection.getCurrentPath() + "." + recipeId,
                            "error", e.getMessage());
                }
            }
        }
        I18n.logDetail("recipe", "recipe.custom_cooking_pot_loaded", "count", loadedCount);
    }

    private List<CookingPotRecipe> sortedRecipeList(Map<String, CookingPotRecipe> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        List<CookingPotRecipe> sorted = new ArrayList<>(source.values());
        sorted.sort(Comparator.comparingInt(CookingPotRecipe::getPriority).reversed()
                .thenComparing(CookingPotRecipe::getId));
        return Collections.unmodifiableList(sorted);
    }

    private void indexDefaultRecipe(Map<String, Set<String>> ingredientIndex, String recipeId, CookingPotRecipe recipe) {
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            for (String ingredientKey : flattenIngredientKeys(ingredient)) {
                ingredientIndex.computeIfAbsent(ingredientKey, k -> new HashSet<>()).add(recipeId);
            }
        }
    }

    private void indexCustomRecipe(Map<String, Map<String, Set<String>>> customIndex, String groupId, String recipeId, CookingPotRecipe recipe) {
        Map<String, Set<String>> groupIndex = customIndex.computeIfAbsent(groupId, key -> new HashMap<>());
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            for (String ingredientKey : flattenIngredientKeys(ingredient)) {
                groupIndex.computeIfAbsent(ingredientKey, key -> new HashSet<>()).add(recipeId);
            }
        }
    }

    private void indexContainer(Set<String> containerKeys, CookingPotRecipe recipe) {
        ItemStack container = recipe.getContainer();
        if (container != null && !container.getType().isAir()) {
            String customId = ItemUtils.getCustomItemId(container);
            if (customId != null) {
                containerKeys.add(customId);
            }
            containerKeys.add("minecraft:" + container.getType().name().toLowerCase(Locale.ROOT));
        }
    }

    private Map<String, List<CookingPotRecipe>> buildResultIndex(List<CookingPotRecipe> recipesToIndex) {
        Map<String, List<CookingPotRecipe>> index = new HashMap<>();
        for (CookingPotRecipe recipe : recipesToIndex) {
            if (recipe.getResult() == null) {
                continue;
            }
            index.computeIfAbsent(getItemKey(recipe.getResult()), k -> new ArrayList<>(1)).add(recipe);
        }
        return index;
    }

    /** Recipes (default + external, excluding custom-group duplicates) that produce this item. */
    public List<CookingPotRecipe> getRecipesProducing(ItemStack item) {
        Snapshot view = snapshot;
        if (item == null || item.getType().isAir()) {
            return List.of();
        }
        List<CookingPotRecipe> matches = view.resultToRecipes().get(getItemKey(item));
        return matches == null ? List.of() : matches;
    }

    private CookingPotRecipe parseRecipe(String id, ConfigurationSection section, int maxIngredients) {
        Object rawIngredients = section.get("ingredients");
        if (!(rawIngredients instanceof List<?> ingredientValues) || ingredientValues.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one ingredient");
        }
        if (ingredientValues.size() > maxIngredients) {
            throw new IllegalArgumentException("Recipe can have at most " + maxIngredients + " ingredients");
        }

        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (int ingredientIndex = 0; ingredientIndex < ingredientValues.size(); ingredientIndex++) {
            Object rawIngredient = ingredientValues.get(ingredientIndex);
            if (rawIngredient instanceof ConfigurationSection nested) {
                rawIngredient = sectionToMap(nested);
            }
            try {
                RecipeIngredient ingredient = RecipeParsingSupport.parseIngredientValue(rawIngredient);
                if (!ingredientHasMembers(ingredient)) {
                    throw new IllegalArgumentException("Ingredient item or tag has no loaded items at ingredients[" + ingredientIndex + "]: " + rawIngredient);
                }
                ingredients.add(ingredient);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid ingredient at ingredients[" + ingredientIndex + "]: " + e.getMessage(), e);
            }
        }

        Object containerValue = section.get("container");
        // "container: none" (also "air" or an empty string) is the explicit opt-out: this recipe needs no
        // container even though its result declares a remainder. Without the field at all the container is
        // inferred from the result below, which is what lets a hand-written or addon recipe behave like the
        // mod's own container-carrying recipes without repeating the container on every entry.
        ItemStack container = null;
        if (containerValue != null && !isContainerOptOut(containerValue)) {
            container = parseItemValue(containerValue);
            if (container == null || container.getType().isAir()) {
                throw new IllegalArgumentException("Invalid container item: " + containerValue);
            }
        }

        Object resultValue = section.get("result");
        if (resultValue == null) {
            throw new IllegalArgumentException("Recipe must have a result");
        }
        ItemStack result = parseItemValue(resultValue);
        if (result == null) {
            throw new IllegalArgumentException("Invalid result item: " + resultValue);
        }
        if (!(resultValue instanceof Map)) {
            result.setAmount(Math.max(1, ConfigSectionReader.optionalInt(section, "result-count", 1)));
        }

        if (container == null && containerValue == null) {
            ItemStack inferred = inferContainer(result, id);
            if (inferred != null) {
                container = inferred;
            }
        }
        boolean needsContainer = container != null && !container.getType().isAir();

        float experience = Math.max(0, (float) ConfigSectionReader.optionalDouble(section, "experience", 0.0));
        int defaultCookTime = Math.max(1, plugin.getConfigInt(Constants.DEFAULT_COOKING_TIME_COOKING_POT,
                "cooking-pot.cooking.default-cook-time",
                "cooking-pot.default-cook-time"));
        int minCookTime = Math.max(1, plugin.getConfigInt(20,
                "cooking-pot.cooking.min-cook-time",
                "cooking-pot.min-cook-time"));
        int maxCookTime = Math.max(minCookTime, plugin.getConfigInt(6000,
                "cooking-pot.cooking.max-cook-time",
                "cooking-pot.max-cook-time"));
        int cookTime = Math.max(minCookTime, Math.min(maxCookTime,
                ConfigSectionReader.optionalInt(section, "cooking_time", defaultCookTime,
                        "cooking-time", "cook-time")));
        String category = ConfigSectionReader.optionalString(section, "category", "misc");
        int priority = ConfigSectionReader.optionalInt(section, "priority", 0);

        return new CookingPotRecipe(id, ingredients, container, needsContainer, result, experience, cookTime, category, priority);
    }

    /**
     * Values of the {@code container} field that explicitly declare "this recipe needs no container", so a
     * result whose own remainder would otherwise be inferred (a soup's bowl, a drink's bottle) can still be
     * cooked without one. Written as a string, because a map value is always a real item snapshot.
     */
    static boolean isContainerOptOut(Object containerValue) {
        if (!(containerValue instanceof String text)) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.equals("none") || normalized.equals("air");
    }

    /**
     * The container a recipe needs, taken from its result item's own remainder (a soup's bowl, a drink's
     * bottle): what a meal leaves behind when eaten is the container it is served in. Null when the inference
     * is switched off, when the result declares nothing, or when the remainder is a configured tool remainder.
     */
    private ItemStack inferContainer(ItemStack result, String recipeId) {
        if (!plugin.getConfigBoolean(true, "cooking-pot.container-inference.enabled",
                "container-inference.enabled")) {
            return null;
        }
        Set<String> excluded = excludedContainerRemainders();
        ItemStack inferred = ItemUtils.craftingRemainderOf(result, recipeId);
        if (inferred == null || inferred.getType().isAir()) {
            return null;
        }
        String customId = ItemUtils.getCustomItemId(inferred);
        String vanillaId = ItemUtils.getVanillaMaterialItemId(inferred);
        return isExcludedRemainder(excluded, customId, vanillaId) ? null : inferred;
    }

    private Set<String> excludedContainerRemainders() {
        List<String> configured = plugin.getConfigStringList("cooking-pot.container-inference.excluded-remainders",
                "container-inference.excluded-remainders");
        if (configured.isEmpty()) {
            return Set.of();
        }
        Set<String> excluded = new HashSet<>(configured.size());
        for (String id : configured) {
            if (id != null && !id.isBlank()) {
                excluded.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return excluded;
    }

    /**
     * True when the inferred container must be dropped because it is a configured tool remainder. Ids are
     * compared case-insensitively, and either id form matches, so one entry covers a vanilla item and a
     * CraftEngine item built on it.
     */
    static boolean isExcludedRemainder(Set<String> excluded, String customId, String vanillaId) {
        if (excluded.isEmpty()) {
            return false;
        }
        return (customId != null && excluded.contains(customId.toLowerCase(Locale.ROOT)))
                || (vanillaId != null && excluded.contains(vanillaId.toLowerCase(Locale.ROOT)));
    }

    private boolean ingredientHasMembers(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            ItemStack stack = item.createStack();
            return stack != null && !stack.getType().isAir();
        }
        if (ingredient instanceof RecipeIngredient.Tag tag) {
            return !plugin.getCraftEngine().itemManager().itemIdsByTag(tag.key()).isEmpty()
                    || !getVanillaItemIdsByTag(tag.key()).isEmpty()
                    || !CommonTagResolver.getMembers(tag.key()).isEmpty();
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            return choice.options().stream().anyMatch(this::ingredientHasMembers);
        }
        return false;
    }

    private RecipeIngredient parseIngredient(String str) {
        return RecipeParsingSupport.parseIngredientChoice(str);
    }

    private List<String> flattenIngredientKeys(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            return List.of(itemIngredient.key().toString());
        }
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return List.of("#" + tagIngredient.key());
        }
        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            List<String> keys = new ArrayList<>();
            for (RecipeIngredient option : choiceIngredient.options()) {
                keys.addAll(flattenIngredientKeys(option));
            }
            return keys;
        }
        return List.of();
    }

    private ItemStack createItem(String itemId) {
        return ItemUtils.createItem(itemId);
    }

    private ItemStack parseItemValue(Object value) {
        // Bukkit deserializes nested YAML maps into ConfigurationSection, not java.util.Map, so item
        // objects written in recipes must be converted to a plain map first.
        if (value instanceof ConfigurationSection section) {
            return RecipeItemCodec.deserializeItem(sectionToMap(section));
        }
        if (value instanceof Map<?, ?> map) {
            return RecipeItemCodec.deserializeItem(RecipeItemCodec.coerceStringMap(map));
        }
        return createItem(value.toString());
    }

    // Recursively flattens a configuration section into a plain map so nested sections (e.g. the
    // components map) survive the conversion and reach the item deserializer unchanged.
    private static Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                map.put(key, sectionToMap(nested));
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    public CookingPotRecipe matchRecipe(List<ItemStack> inputItems, ItemStack container) {
        return matchRecipe(inputItems, container, null);
    }

    public CookingPotRecipe matchRecipe(List<ItemStack> inputItems, ItemStack container, String customRecipeGroupId) {
        if (inputItems == null || inputItems.isEmpty()) {
            return null;
        }
        
        List<ItemStack> nonEmptyInputs = new ArrayList<>();
        for (ItemStack item : inputItems) {
            if (item != null && item.getType() != Material.AIR) {
                nonEmptyInputs.add(item);
            }
        }
        
        if (nonEmptyInputs.isEmpty()) {
            return null;
        }

        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        String cacheKey = buildCacheKey(nonEmptyInputs, container, normalizedGroupId);
        // One capture binds matching, indices and cache entries to the same recipe version.
        Snapshot view = snapshot;
        CookingPotRecipe cached;
        boolean cachedMiss;
        synchronized (view.recipeCache()) {
            cached = view.recipeCache().get(cacheKey);
            cachedMiss = cached == null && view.recipeMisses().contains(cacheKey);
        }
        if (cachedMiss) {
            return null;
        }
        if (cached != null) {
            return cached;
        }

        CookingPotRecipe result = null;
        if (normalizedGroupId != null) {
            result = matchCustomRecipe(nonEmptyInputs, container, normalizedGroupId, view);
        }

        if (result == null) {
            result = matchDefaultRecipe(nonEmptyInputs, container, view);
        }

        synchronized (view.recipeCache()) {
            // A retired snapshot may finish matching, but its cache cannot populate the current snapshot.
            if (snapshot == view) {
                if (result != null) {
                    view.recipeCache().put(cacheKey, result);
                } else {
                    // Negative cache so an unchanged incomplete pot won't re-scan every recipe next tick.
                    view.recipeMisses().add(cacheKey);
                }
            }
        }

        return result;
    }

    private CookingPotRecipe matchCustomRecipe(List<ItemStack> nonEmptyInputs, ItemStack container, String customRecipeGroupId, Snapshot view) {
        Map<String, CookingPotRecipe> groupRecipes = view.customRecipes().get(customRecipeGroupId);
        if (groupRecipes == null || groupRecipes.isEmpty()) {
            return null;
        }
        Set<String> candidateRecipes = findCandidateRecipes(nonEmptyInputs, view.customIngredientToRecipes().get(customRecipeGroupId));
        List<CookingPotRecipe> orderedRecipes = view.sortedCustomOnlyRecipes().getOrDefault(customRecipeGroupId, List.of());
        CookingPotRecipe matched = matchFirstRecipe(orderedRecipes, candidateRecipes, container, nonEmptyInputs);
        if (matched != null) {
            return matched;
        }
        if (candidateRecipes != null) {
            return matchFirstRecipe(orderedRecipes, null, container, nonEmptyInputs);
        }
        return null;
    }

    private CookingPotRecipe matchDefaultRecipe(List<ItemStack> nonEmptyInputs, ItemStack container, Snapshot view) {
        Set<String> candidateRecipes = findCandidateRecipes(nonEmptyInputs, view.ingredientToRecipes());

        CookingPotRecipe matched = matchFirstRecipe(view.sortedRecipes(), candidateRecipes, container, nonEmptyInputs);
        if (matched != null) {
            return matched;
        }
        if (candidateRecipes != null) {
            return matchFirstRecipe(view.sortedRecipes(), null, container, nonEmptyInputs);
        }
        return null;
    }

    private CookingPotRecipe matchFirstRecipe(List<CookingPotRecipe> orderedRecipes, Set<String> candidateRecipeIds,
                                              ItemStack container, List<ItemStack> nonEmptyInputs) {
        if (orderedRecipes == null || orderedRecipes.isEmpty()) {
            return null;
        }
        // Prefer a recipe that consumes exactly the filled slots; only when none does, allow a lenient match
        // (extra slots of an ingredient the recipe already uses), so exact recipes are never shadowed.
        // The C slot is a batch-output channel, not a cook gate: the recipe's required container is
        // checked at extraction time (storeCookedResult / tryMovePendingToOutput), never here.
        CookingPotRecipe exact = matchPass(orderedRecipes, candidateRecipeIds, nonEmptyInputs, true);
        if (exact != null) {
            return exact;
        }
        return matchPass(orderedRecipes, candidateRecipeIds, nonEmptyInputs, false);
    }

    private CookingPotRecipe matchPass(List<CookingPotRecipe> orderedRecipes, Set<String> candidateRecipeIds,
                                       List<ItemStack> nonEmptyInputs, boolean exactSlots) {
        for (CookingPotRecipe recipe : orderedRecipes) {
            if (candidateRecipeIds != null && !candidateRecipeIds.contains(recipe.getId())) {
                continue;
            }
            // matchRecipePrefiltered skips the per-call ArrayList alloc that matchRecipe's defensive
            // filter does — caller (matchRecipe public) has already stripped nulls/airs into the list,
            // and matchPass runs this in a tight loop over every recipe in orderedRecipes.
            if (matchRecipePrefiltered(recipe, nonEmptyInputs, exactSlots)) {
                return recipe;
            }
        }
        return null;
    }

    private String buildCacheKey(List<ItemStack> inputs, ItemStack container, String customRecipeGroupId) {
        List<String> keys = new ArrayList<>();
        // Clamp the per-slot amount that goes into the key. IngredientMatching caps each slot at
        // min(amount, ingredientCount) interchangeable units, and only recipes with
        // ingredientCount <= inputs.size() can pass its slot-count gate, so any amount above
        // inputs.size() is indistinguishable to the matcher. Collapsing it keeps a hopper-fed pot
        // whose stacks keep growing on one cache entry instead of evicting the whole LRU each tick.
        int amountCap = inputs.size();
        for (ItemStack item : inputs) {
            keys.add(getItemKey(item) + ":" + Math.min(item.getAmount(), amountCap));
        }
        Collections.sort(keys);

        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            sb.append(key).append(";");
        }
        sb.append("|container=").append(getItemKey(container));
        if (customRecipeGroupId != null) {
            sb.append("|group=").append(customRecipeGroupId);
        }
        return sb.toString();
    }

    private Set<String> findCandidateRecipes(List<ItemStack> inputs, Map<String, Set<String>> recipeIndex) {
        if (recipeIndex == null || recipeIndex.isEmpty()) {
            return null;
        }
        // Copy-on-write avoids per-call HashSet allocations when only one source set needs merging.
        // candidates / recipesForItem start as shared references to an unmodified index entry; we
        // allocate a real HashSet copy only when a second source forces a union or intersection.
        Set<String> candidates = null;
        boolean candidatesShared = false;

        for (ItemStack item : inputs) {
            Set<String> recipesForItem = null;
            boolean recipesForItemShared = false;

            for (String itemId : ItemUtils.getItemIds(item)) {
                Set<String> indexed = recipeIndex.get(itemId);
                if (indexed == null || indexed.isEmpty()) {
                    continue;
                }
                if (recipesForItem == null) {
                    recipesForItem = indexed;
                    recipesForItemShared = true;
                } else {
                    if (recipesForItemShared) {
                        recipesForItem = new HashSet<>(recipesForItem);
                        recipesForItemShared = false;
                    }
                    recipesForItem.addAll(indexed);
                }
            }
            for (String tagId : ItemUtils.getItemTagIds(item)) {
                Set<String> indexed = recipeIndex.get("#" + tagId);
                if (indexed == null || indexed.isEmpty()) {
                    continue;
                }
                if (recipesForItem == null) {
                    recipesForItem = indexed;
                    recipesForItemShared = true;
                } else {
                    if (recipesForItemShared) {
                        recipesForItem = new HashSet<>(recipesForItem);
                        recipesForItemShared = false;
                    }
                    recipesForItem.addAll(indexed);
                }
            }

            if (recipesForItem != null) {
                if (candidates == null) {
                    candidates = recipesForItem;
                    candidatesShared = recipesForItemShared;
                } else {
                    if (candidatesShared) {
                        candidates = new HashSet<>(candidates);
                        candidatesShared = false;
                    }
                    candidates.retainAll(recipesForItem);
                }
            }
        }

        return candidates;
    }

    private boolean matchRecipe(CookingPotRecipe recipe, List<ItemStack> inputs) {
        // Lenient acceptance: an exact-slot match also satisfies this, so canCraft uses it.
        return matchRecipe(recipe, inputs, false);
    }

    private boolean matchRecipe(CookingPotRecipe recipe, List<ItemStack> inputs, boolean exactSlots) {
        List<ItemStack> nonEmpty = new ArrayList<>();
        for (ItemStack input : inputs) {
            if (input != null && !input.getType().isAir()) {
                nonEmpty.add(input);
            }
        }
        return matchRecipePrefiltered(recipe, nonEmpty, exactSlots);
    }

    private boolean matchRecipePrefiltered(CookingPotRecipe recipe, List<ItemStack> nonEmptyInputs, boolean exactSlots) {
        // Exact matching gives each filled slot a budget of one unit so every slot matches one ingredient.
        // Using stack amounts here could satisfy several ingredients from one slot and leave another unused.
        // Lenient matching uses stack amounts to support ingredients spread across slots,
        // while separately requiring every filled slot to contain a usable ingredient.
        ToIntFunction<ItemStack> unitBudget = exactSlots ? slot -> 1 : ItemStack::getAmount;
        return IngredientMatching.matchesIngredients(
                recipe.getIngredients(), nonEmptyInputs, exactSlots,
                this::matchIngredient, unitBudget);
    }

    public boolean canCraft(CookingPotRecipe recipe, List<ItemStack> inputs) {
        return recipe != null && matchRecipe(recipe, inputs);
    }

    public boolean matchesIngredient(ItemStack item, RecipeIngredient ingredient) {
        return matchIngredient(item, ingredient);
    }

    private boolean matchIngredient(ItemStack item, RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            if (!ItemUtils.matchesItemId(item, itemIngredient.key())) {
                return false;
            }
            if (itemIngredient.nbt() == null) {
                return true;
            }
            ItemStack expected = RecipeItemCodec.itemFromBase64(itemIngredient.nbt());
            return expected != null && expected.isSimilar(item);
        } else if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            for (RecipeIngredient option : choiceIngredient.options()) {
                if (matchIngredient(item, option)) {
                    return true;
                }
            }
            return false;
        } else if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            String vanillaId = ItemUtils.getVanillaMaterialItemId(item);

            if (tagIngredient.excludedItems().stream().anyMatch(excluded -> ItemUtils.matchesItemId(item, excluded))) {
                return false;
            }

            Set<String> itemTags = ItemUtils.getItemTagIds(item);
            if (itemTags.contains(tagIngredient.key().toString())) {
                for (Key excludedTag : tagIngredient.excludedTags()) {
                    if (itemTags.contains(excludedTag.toString())) {
                        return false;
                    }
                }
                return true;
            }

            Set<String> vanillaTags = getVanillaItemIdsByTag(tagIngredient.key());
            boolean matchesBase = vanillaId != null && (vanillaTags.contains(vanillaId)
                    || ItemUtils.matchesVanillaItemTag(item, tagIngredient.key(),
                    tagIngredient.excludedItems(), tagIngredient.excludedTags()));
            if (!matchesBase) {
                return false;
            }
            for (Key excludedTag : tagIngredient.excludedTags()) {
                boolean blocked = vanillaId != null && getVanillaItemIdsByTag(excludedTag).contains(vanillaId);
                if (blocked) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public Set<String> getVanillaItemIdsByTag(Key tagKey) {
        return vanillaItemIdsByTagCache.getIds(tagKey);
    }

    private String getItemKey(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "none";
        }

        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        // Distinct MMOItems items can share a base material; key on their identity so different
        // mmoitems:<TYPE>:<ID> items never collide in the recipe cache.
        String mmoId = MMOItemsCompat.getItemId(item);
        if (mmoId != null) {
            return mmoId;
        }
        return ItemUtils.getVanillaMaterialItemId(item);
    }

    public Map<String, CookingPotRecipe> getRecipes() {
        Snapshot view = snapshot;
        return Collections.unmodifiableMap(view.recipes());
    }

    public List<CookingPotRecipe> getAllRecipes() {
        Snapshot view = snapshot;
        Map<String, CookingPotRecipe> defaultRecipes = view.recipes();
        Map<String, Map<String, CookingPotRecipe>> groupedRecipes = view.customRecipes();
        List<CookingPotRecipe> all = new ArrayList<>(defaultRecipes.values());
        for (Map<String, CookingPotRecipe> groupRecipes : groupedRecipes.values()) {
            all.addAll(groupRecipes.values());
        }
        return Collections.unmodifiableList(all);
    }

    public Map<String, CookingPotRecipe> getRecipes(String customRecipeGroupId) {
        Snapshot view = snapshot;
        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        if (normalizedGroupId == null) {
            return view.recipes();
        }
        Map<String, CookingPotRecipe> groupRecipes = view.customRecipes().get(normalizedGroupId);
        if (groupRecipes == null || groupRecipes.isEmpty()) {
            return view.recipes();
        }
        Map<String, CookingPotRecipe> merged = new LinkedHashMap<>(view.recipes());
        merged.putAll(groupRecipes);
        return Collections.unmodifiableMap(merged);
    }

    public List<CookingPotRecipe> getSortedRecipes(String customRecipeGroupId) {
        Snapshot view = snapshot;
        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        if (normalizedGroupId == null) {
            return view.sortedRecipes();
        }
        return view.sortedCustomRecipes().getOrDefault(normalizedGroupId, view.sortedRecipes());
    }

    public Set<String> getValidContainerKeys() {
        Snapshot view = snapshot;
        return view.validContainerKeys();
    }

    public int getRecipeCount() {
        Snapshot view = snapshot;
        return view.recipes().size();
    }

    public int getExternalRecipeCount() {
        return externalRecipes.size();
    }

    /** Recipes that reached this manager through a CraftEngine pack section, not the plugin's own file. */
    public int getPackRecipeCount() {
        Snapshot view = snapshot;
        return view.packRecipeCount();
    }

    public boolean isExternalRecipe(String id) {
        return id != null && externalRecipes.containsKey(id);
    }

    private static Set<String> externalOverrideIds(YamlConfiguration config, String station) {
        return Set.copyOf(config.getStringList("external-overrides." + station));
    }

    public int getCustomRecipeCount() {
        Snapshot view = snapshot;
        int count = 0;
        for (Map<String, CookingPotRecipe> groupRecipes : view.customRecipes().values()) {
            count += groupRecipes.size();
        }
        return count;
    }

    public CookingPotRecipe getRecipe(String id) {
        Snapshot view = snapshot;
        return view.recipes().get(id);
    }

    public CookingPotRecipe getRecipe(String customRecipeGroupId, String id) {
        Snapshot view = snapshot;
        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        if (normalizedGroupId == null) {
            return view.recipes().get(id);
        }
        Map<String, CookingPotRecipe> groupRecipes = view.customRecipes().get(normalizedGroupId);
        CookingPotRecipe customRecipe = groupRecipes == null ? null : groupRecipes.get(id);
        return customRecipe != null ? customRecipe : view.recipes().get(id);
    }

    public void reload() {
        loadRecipes();
    }

    public void registerExternalRecipe(String id, List<String> ingredientSpecs, ItemStack container,
                                       ItemStack result, float experience, int cookTime, String category) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Recipe id is required");
        }
        if (ingredientSpecs == null || ingredientSpecs.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one ingredient");
        }
        if (result == null || result.getType().isAir()) {
            throw new IllegalArgumentException("Recipe must have a result");
        }
        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (int i = 0; i < ingredientSpecs.size(); i++) {
            String spec = ingredientSpecs.get(i);
            if (spec == null || spec.isBlank()) {
                throw new IllegalArgumentException("Invalid ingredient at ingredients[" + i + "]: empty");
            }
            ingredients.add(parseIngredient(spec));
        }
        boolean needsContainer = container != null && !container.getType().isAir();
        CookingPotRecipe recipe = new CookingPotRecipe(id, ingredients, needsContainer ? container : null,
                needsContainer, result, Math.max(0f, experience), Math.max(1, cookTime),
                category == null ? "misc" : category, 0);
        externalRecipes.put(id, recipe);
        scheduleExternalRepublish();
    }

    public void unregisterExternalRecipe(String id) {
        if (id != null && externalRecipes.remove(id) != null) {
            scheduleExternalRepublish();
        }
    }

    private void scheduleExternalRepublish() {
        if (!externalRepublishScheduled.compareAndSet(false, true)) return;
        try {
            plugin.scheduler().runLater(() -> {
                externalRepublishScheduled.set(false);
                loadRecipes(lastFileDocument, true);
                // This republish runs after the CraftEngine readiness pass already printed the summary, so
                // without re-reporting the cooking pot count on the console stays one batch behind whatever
                // addons registered. The summary dedupes on its counts digest, making this a no-op when the
                // batch did not move a count.
                plugin.requestContentSummary();
            }, 1L);
        } catch (RuntimeException stoppedScheduler) {
            externalRepublishScheduled.set(false);
            throw stoppedScheduler;
        }
    }
    
    public void clearCache() {
        Snapshot view = snapshot;
        synchronized (view.recipeCache()) {
            view.recipeCache().clear();
            view.recipeMisses().clear();
        }
    }

    private String normalizeRecipeGroupId(String customRecipeGroupId) {
        if (customRecipeGroupId == null || customRecipeGroupId.isBlank()) {
            return null;
        }
        return customRecipeGroupId.trim();
    }
}
