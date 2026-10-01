package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.config.YamlFileTransactions;
import com.huidu.farmersdelight.recipe.RecipeFileLoader;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeItemCodec;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.recipe.AddonRecipeFiles;
import com.huidu.farmersdelight.api.recipe.AddonRecipeFiles.RecipeOwner;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class RecipeEditorStore {

    private static final String COOKING_POT_FILE = "recipes/cooking_pot_recipes.yml";
    private static final String CUTTING_BOARD_FILE = "recipes/cutting_board_recipes.yml";

    private static final String COOKING_POT_ROOT = "cooking_pot_recipes";
    private static final String CUSTOM_COOKING_POT_ROOT = "custom_cooking_pot_recipes";
    private static final String CUTTING_BOARD_ROOT = "cutting_board_recipes";
    private static final String EXTERNAL_OVERRIDES_ROOT = "external-overrides";
    /** Written when a recipe must keep no container although its result declares one (see the recipe loader). */
    static final String CONTAINER_OPT_OUT = "none";

    private final FarmersDelightPlugin plugin;

    public RecipeEditorStore(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    private record Edit(File file, String localPath, YamlMutation mutation) {
    }

    public boolean saveCookingPotRecipe(CookingPotRecipe recipe, String customGroupId) {
        return captureAndReload(() -> cookingPotEdit(recipe, customGroupId));
    }

    public CompletableFuture<Boolean> saveCookingPotRecipeAsync(CookingPotRecipe recipe, String customGroupId) {
        return captureAsync(() -> cookingPotEdit(recipe, customGroupId));
    }

    private Edit cookingPotEdit(CookingPotRecipe recipe, String group) {
        // CraftEngine/NMS item serialization belongs to the caller's owning thread.
        Map<String, Object> body = buildCookingPotBody(recipe);
        if (group == null || group.isBlank()) {
            RecipeOwner owner = AddonRecipeFiles.ownerOf("cooking_pot", recipe.getId());
            if (owner != null) {
                return external(owner, yaml -> yaml.set(owner.yamlPath(), body));
            }
            if (plugin.getCookingPotRecipes().isExternalRecipe(recipe.getId())) {
                return local(COOKING_POT_FILE, yaml -> {
                    putRecipe(yaml, COOKING_POT_ROOT, recipe.getId(), body);
                    setExternalOverride(yaml, "cooking_pot", recipe.getId(), true);
                });
            }
        }
        String path = cookingPotPath(recipe.getId(), group);
        return local(COOKING_POT_FILE, yaml -> yaml.set(path, body));
    }

    public boolean deleteCookingPotRecipe(String recipeId, String customGroupId) {
        return captureAndReload(() -> deleteCookingPotEdit(recipeId, customGroupId));
    }

    public CompletableFuture<Boolean> deleteCookingPotRecipeAsync(String recipeId, String customGroupId) {
        return captureAsync(() -> deleteCookingPotEdit(recipeId, customGroupId));
    }

    private Edit deleteCookingPotEdit(String id, String group) {
        if (group == null || group.isBlank()) {
            RecipeOwner owner = AddonRecipeFiles.ownerOf("cooking_pot", id);
            if (owner != null) {
                return external(owner, yaml -> yaml.set(owner.yamlPath(), null));
            }
            if (plugin.getCookingPotRecipes().isExternalRecipe(id)) {
                return local(COOKING_POT_FILE, yaml -> {
                    putRecipe(yaml, COOKING_POT_ROOT, id, null);
                    setExternalOverride(yaml, "cooking_pot", id, false);
                });
            }
        }
        String path = cookingPotPath(id, group);
        return local(COOKING_POT_FILE, yaml -> yaml.set(path, null));
    }

    public boolean saveCuttingBoardRecipe(CuttingBoardRecipe recipe) {
        return captureAndReload(() -> cuttingBoardEdit(recipe));
    }

    public CompletableFuture<Boolean> saveCuttingBoardRecipeAsync(CuttingBoardRecipe recipe) {
        return captureAsync(() -> cuttingBoardEdit(recipe));
    }

    private Edit cuttingBoardEdit(CuttingBoardRecipe recipe) {
        Map<String, Object> body = buildCuttingBoardBody(recipe);
        String id = recipe.getId();
        RecipeOwner owner = AddonRecipeFiles.ownerOf("cutting_board", id);
        if (owner != null) {
            return external(owner, yaml -> yaml.set(owner.yamlPath(), body));
        }
        if (plugin.getCuttingBoardRecipes().isExternalRecipe(id)) {
            return local(CUTTING_BOARD_FILE, yaml -> {
                putRecipe(yaml, CUTTING_BOARD_ROOT, id, body);
                setExternalOverride(yaml, "cutting_board", id, true);
            });
        }
        return local(CUTTING_BOARD_FILE, yaml -> yaml.set(CUTTING_BOARD_ROOT + "." + id, body));
    }

    public boolean deleteCuttingBoardRecipe(String recipeId) {
        return captureAndReload(() -> deleteCuttingBoardEdit(recipeId));
    }

    public CompletableFuture<Boolean> deleteCuttingBoardRecipeAsync(String recipeId) {
        return captureAsync(() -> deleteCuttingBoardEdit(recipeId));
    }

    private Edit deleteCuttingBoardEdit(String id) {
        RecipeOwner owner = AddonRecipeFiles.ownerOf("cutting_board", id);
        if (owner != null) {
            return external(owner, yaml -> yaml.set(owner.yamlPath(), null));
        }
        if (plugin.getCuttingBoardRecipes().isExternalRecipe(id)) {
            return local(CUTTING_BOARD_FILE, yaml -> {
                putRecipe(yaml, CUTTING_BOARD_ROOT, id, null);
                setExternalOverride(yaml, "cutting_board", id, false);
            });
        }
        return local(CUTTING_BOARD_FILE, yaml -> yaml.set(CUTTING_BOARD_ROOT + "." + id, null));
    }

    private Edit local(String path, YamlMutation mutation) {
        return new Edit(new File(plugin.getDataFolder(), path), path, mutation);
    }

    private Edit external(RecipeOwner owner, YamlMutation mutation) {
        return new Edit(owner.file(), null, mutation);
    }

    private String cookingPotPath(String recipeId, String customGroupId) {
        if (customGroupId == null || customGroupId.isBlank()) {
            return COOKING_POT_ROOT + "." + recipeId;
        }
        return CUSTOM_COOKING_POT_ROOT + "." + customGroupId + "." + recipeId;
    }

    private Map<String, Object> buildCookingPotBody(CookingPotRecipe recipe) {
        Map<String, Object> body = new LinkedHashMap<>();

        List<Object> ingredients = new ArrayList<>();
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            ingredients.add(RecipeSerializer.serializeIngredientValue(ingredient));
        }
        body.put("ingredients", ingredients);

        ItemStack container = recipe.getContainer();
        if (container != null && !container.getType().isAir()) {
            Map<String, Object> snapshot = RecipeItemCodec.snapshotIfCustom(container);
            body.put("container", snapshot != null ? snapshot : RecipeSerializer.itemIdString(container));
        } else if (recipe.getResult() != null && !recipe.getResult().getType().isAir()
                && ItemUtils.craftingRemainderOf(recipe.getResult(), recipe.getId()) != null) {
            // Saved without a container while the result declares one: write the explicit opt-out, otherwise
            // loading the file would infer that container right back.
            body.put("container", CONTAINER_OPT_OUT);
        }

        ItemStack result = recipe.getResult();
        Map<String, Object> resultSnapshot = RecipeItemCodec.snapshotIfCustom(result);
        if (resultSnapshot != null) {
            body.put("result", resultSnapshot);
        } else {
            body.put("result", RecipeSerializer.itemIdString(result));
            if (result != null && result.getAmount() > 1) {
                body.put("result-count", result.getAmount());
            }
        }
        if (recipe.getExperience() > 0.0f) {
            body.put("experience", (double) recipe.getExperience());
        }
        body.put("cook-time", recipe.getCookTime());
        if (recipe.getCategory() != null && !recipe.getCategory().isBlank()) {
            body.put("category", recipe.getCategory());
        }
        if (recipe.getPriority() != 0) {
            body.put("priority", recipe.getPriority());
        }
        return body;
    }

    private Map<String, Object> buildCuttingBoardBody(CuttingBoardRecipe recipe) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", RecipeSerializer.serializeIngredientValue(recipe.getInput()));

        List<String> tools = new ArrayList<>();
        for (CuttingBoardRecipe.ToolRequirement tool : recipe.getTools()) {
            tools.add(RecipeSerializer.serializeTool(tool));
        }
        if (tools.size() == 1) {
            body.put("tool", tools.getFirst());
        } else if (!tools.isEmpty()) {
            body.put("tools", tools);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (CuttingBoardRecipe.ResultEntry entry : recipe.getResults()) {
            ItemStack item = entry.getItem();
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Map<String, Object> resultMap = new LinkedHashMap<>();
            Map<String, Object> snapshot = RecipeItemCodec.snapshotIfCustom(item);
            if (snapshot != null) {
                resultMap.putAll(snapshot);
            } else {
                resultMap.put("item", RecipeSerializer.itemIdString(item));
                if (item.getAmount() > 1) {
                    resultMap.put("count", item.getAmount());
                }
            }
            if (entry.getChance() < 1.0d) {
                resultMap.put("chance", entry.getChance());
            }
            results.add(resultMap);
        }
        body.put("results", results);

        if (recipe.getSound() != null && !recipe.getSound().isBlank()
                && !recipe.getSound().equals(Constants.SOUND_CUTTING_BOARD_KNIFE)) {
            body.put("sound", recipe.getSound());
        }
        if (recipe.getPriority() != 0) {
            body.put("priority", recipe.getPriority());
        }
        return body;
    }

    private interface YamlMutation {
        void apply(YamlConfiguration yaml);
    }

    private boolean apply(Edit edit) {
        try {
            return YamlFileTransactions.execute(edit.file().toPath(), () -> {
                YamlConfiguration yaml;
                if (edit.localPath() != null) {
                    yaml = RecipeFileLoader.loadPlainRecipeFile(plugin, edit.localPath());
                    if (yaml == null) {
                        throw new IOException("Cannot read recipe file; edit was refused");
                    }
                } else {
                    // Strict plain parsing keeps legacy serialization maps untouched during worker I/O.
                    yaml = com.huidu.farmersdelight.config.PlainYamlDocuments.read(edit.file().toPath());
                }
                edit.mutation().apply(yaml);
                writeAtomically(edit.file(), yaml.saveToString());
                return true;
            });
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            I18n.logWarning("plugin.recipe_save_failed", "file", edit.file().getPath(), "error", error.getMessage());
            return false;
        }
    }

    private boolean captureAndReload(java.util.function.Supplier<Edit> capture) {
        try {
            return applyAndReload(capture.get());
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not capture recipe edit", error);
            return false;
        }
    }

    private CompletableFuture<Boolean> captureAsync(java.util.function.Supplier<Edit> capture) {
        try {
            return applyAsync(capture.get());
        } catch (RuntimeException | LinkageError error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private boolean applyAndReload(Edit edit) {
        if (!apply(edit)) {
            return false;
        }
        plugin.reloadRecipeFiles();
        return true;
    }

    private CompletableFuture<Boolean> applyAsync(Edit edit) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!plugin.scheduler().tryRunAsync(() -> {
            try {
                if (!apply(edit)) {
                    result.complete(false);
                    return;
                }
                if (!plugin.isEnabled()) {
                    result.complete(true); // Disk commit succeeded; startup will publish it next time.
                    return;
                }
                plugin.reloadEditedRecipeFilesAsync().whenComplete((ignored, error) -> {
                    if (error == null) result.complete(true);
                    else result.completeExceptionally(error);
                });
            } catch (RuntimeException | LinkageError error) {
                result.completeExceptionally(error);
            }
        })) {
            result.complete(false);
        }
        return result;
    }

    private static void putRecipe(YamlConfiguration yaml, String rootName, String id, Object value) {
        ConfigurationSection root = yaml.getConfigurationSection(rootName);
        Map<String, Object> entries = root == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(root.getValues(false));
        if (value == null) {
            entries.remove(id);
        } else {
            entries.put(id, value);
        }
        yaml.set(rootName, entries.isEmpty() ? null : entries);
    }

    private static void setExternalOverride(YamlConfiguration yaml, String station, String id, boolean enabled) {
        String path = EXTERNAL_OVERRIDES_ROOT + "." + station;
        List<String> ids = new ArrayList<>(yaml.getStringList(path));
        ids.removeIf(id::equals);
        if (enabled) {
            ids.add(id);
        }
        yaml.set(path, ids.isEmpty() ? null : ids);
    }

    static void writeAtomically(File target, String content) throws IOException {
        ConfigFileUpdater.writeStringAtomically(target.toPath(), content, true);
    }
}
