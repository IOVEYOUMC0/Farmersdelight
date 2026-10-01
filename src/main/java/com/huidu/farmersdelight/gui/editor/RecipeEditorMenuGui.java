package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Hierarchical editor menus; recipe records are cheap, and only visible icons are built. */
public final class RecipeEditorMenuGui implements EditorGui {
    private enum Screen { HOME, POT_GROUPS, POT_RECIPES, BOARD_RECIPES, ADDON_TYPES, ADDON_RECIPES, CREATE_MODE }
    private static final int[] CONTENT = {
            10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private final FarmersDelightPlugin plugin;
    private final Player player;
    private final Screen screen;
    private final String group;
    private final RecipeType addon;
    private final Runnable back;
    private final Inventory inventory;
    private final List<Entry> entries = new ArrayList<>();
    private List<Entry> visible = List.of();
    private int page;
    private int mode; // all, exact, fuzzy
    private String query = "";
    private boolean leaving;
    private boolean filterDirty = true;

    private record Entry(String id, boolean fuzzy, Supplier<ItemStack> icon, Supplier<String> name, Runnable action) { }

    private RecipeEditorMenuGui(FarmersDelightPlugin plugin, Player player, Screen screen,
                                String group, RecipeType addon, Runnable back) {
        this.plugin = plugin;
        this.player = player;
        this.screen = screen;
        this.group = group;
        this.addon = addon;
        this.back = back;
        inventory = plugin.getServer().createInventory(this, 54, text("title_" + screen.name().toLowerCase(Locale.ROOT)));
    }

    public static void openHome(FarmersDelightPlugin plugin, Player player) {
        new RecipeEditorMenuGui(plugin, player, Screen.HOME, null, null, null).show();
    }

    public static void openPotGroups(FarmersDelightPlugin plugin, Player player) {
        new RecipeEditorMenuGui(plugin, player, Screen.POT_GROUPS, null, null, () -> openHome(plugin, player)).show();
    }

    public static void openPotRecipes(FarmersDelightPlugin plugin, Player player, String group) {
        new RecipeEditorMenuGui(plugin, player, Screen.POT_RECIPES, group, null, () -> openPotGroups(plugin, player)).show();
    }

    public static void openBoardRecipes(FarmersDelightPlugin plugin, Player player) {
        new RecipeEditorMenuGui(plugin, player, Screen.BOARD_RECIPES, null, null, () -> openHome(plugin, player)).show();
    }

    public static void openAddonRecipes(FarmersDelightPlugin plugin, Player player, RecipeType type) {
        new RecipeEditorMenuGui(plugin, player, Screen.ADDON_RECIPES, null, type,
                () -> new RecipeEditorMenuGui(plugin, player, Screen.ADDON_TYPES, null, null, () -> openHome(plugin, player)).show()).show();
    }

    private void show() {
        if (!EditorNavigation.allowed(plugin, player)) return;
        RecipeEditorListener.ensureRegistered(plugin);
        RecipeEditorListener.cancelPrompt(player.getUniqueId());
        leaving = false;
        loadEntries();
        render();
        player.openInventory(inventory);
    }

    private void loadEntries() {
        entries.clear();
        filterDirty = true;
        switch (screen) {
            case HOME -> {
                add("pot", Material.CAULDRON, "pot", plugin.getCookingPotRecipes().getRecipeCount() + plugin.getCookingPotRecipes().getCustomRecipeCount(),
                        () -> new RecipeEditorMenuGui(plugin, player, Screen.POT_GROUPS, null, null, this::show).show());
                add("board", Material.OAK_SLAB, "board", plugin.getCuttingBoardRecipes().getRecipes().size(),
                        () -> new RecipeEditorMenuGui(plugin, player, Screen.BOARD_RECIPES, null, null, this::show).show());
                add("groups", Material.NAME_TAG, "food_groups", plugin.getCookingPotRecipes().getLocalFoodGroups().size(),
                        () -> FoodGroupEditorGui.open(plugin, player, this::show));
                if (FarmersDelightApi.get().recipeTypes().stream().anyMatch(type -> type.editor() != null)) {
                    add("addons", Material.KNOWLEDGE_BOOK, "addons", -1,
                            () -> new RecipeEditorMenuGui(plugin, player, Screen.ADDON_TYPES, null, null, this::show).show());
                }
            }
            case POT_GROUPS -> {
                add("", Material.CAULDRON, "default_group", plugin.getCookingPotRecipes().getRecipeCount(),
                        () -> new RecipeEditorMenuGui(plugin, player, Screen.POT_RECIPES, null, null, this::show).show());
                plugin.getCookingPotRecipes().getCustomRecipeGroups().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    String id = entry.getKey();
                    entries.add(new Entry(id, false,
                            () -> icon(new ItemStack(Material.CHEST), Component.text(id), text("count", "count", entry.getValue().size()), text("custom_group_hint")),
                            () -> id, () -> new RecipeEditorMenuGui(plugin, player, Screen.POT_RECIPES, id, null, this::show).show()));
                });
            }
            case POT_RECIPES -> {
                for (CookingPotRecipe recipe : plugin.getCookingPotRecipes().getEditableRecipes(group)) {
                    entries.add(new Entry(recipe.id(), recipe.isFuzzy(),
                            () -> recipeIcon(recipe.result(), recipe.id(), recipe.isFuzzy()),
                            () -> ItemUtils.getDisplayName(recipe.result(), player),
                            () -> RecipeEditorView.open(plugin, player, "pot", recipe.id(), group, this::show)));
                }
            }
            case BOARD_RECIPES -> {
                for (CuttingBoardRecipe recipe : plugin.getCuttingBoardRecipes().getSortedRecipes()) {
                    Supplier<ItemStack> result = () -> recipe.getResults().isEmpty() ? new ItemStack(Material.OAK_SLAB) : recipe.getResults().getFirst().item();
                    entries.add(new Entry(recipe.getId(), false, () -> recipeIcon(result.get(), recipe.getId(), false),
                            () -> ItemUtils.getDisplayName(result.get(), player),
                            () -> RecipeEditorView.open(plugin, player, "board", recipe.getId(), null, this::show)));
                }
            }
            case ADDON_TYPES -> FarmersDelightApi.get().recipeTypes().stream().filter(type -> type.editor() != null)
                    .sorted(Comparator.comparing(RecipeType::id)).forEach(type -> entries.add(new Entry(type.id(), false,
                            () -> icon(type.icon(), type.title(), text("count", "count", type.recipes().size()), text("choose")),
                            type::id, () -> new RecipeEditorMenuGui(plugin, player, Screen.ADDON_RECIPES, null, type, this::show).show())));
            case ADDON_RECIPES -> {
                if (addon.editor() == null) break;
                addon.recipes().stream().sorted(Comparator.comparing(com.huidu.farmersdelight.api.recipe.ViewableRecipe::id)).forEach(recipe ->
                        entries.add(new Entry(recipe.id(), false, () -> recipeIcon(recipe.icon(), recipe.id(), false),
                                () -> ItemUtils.getDisplayName(recipe.icon(), player),
                                () -> RecipeEditorView.open(player, addon, recipe.id(), this::show))));
            }
            case CREATE_MODE -> {
                add("exact", Material.CRAFTING_TABLE, "create_exact", -1, () -> askRecipeId(false));
                add("fuzzy", Material.COOKED_BEEF, "create_fuzzy", -1, () -> askRecipeId(true));
            }
        }
    }

    private void add(String id, Material material, String title, int count, Runnable action) {
        entries.add(new Entry(id, false, () -> count < 0 ? icon(new ItemStack(material), text(title), text("choose"))
                : icon(new ItemStack(material), text(title), text("count", "count", count), text("choose")), () -> I18n.get("gui.editor.menu." + title, player), action));
    }

    private boolean listScreen() {
        return screen == Screen.POT_RECIPES || screen == Screen.BOARD_RECIPES || screen == Screen.ADDON_RECIPES;
    }

    private void render() {
        ItemStack filler = icon(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), Component.text(" "));
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        if (filterDirty) {
            visible = entries.stream().filter(entry -> mode == 0 || mode == 1 && !entry.fuzzy() || mode == 2 && entry.fuzzy())
                    .filter(entry -> query.isEmpty() || entry.id().toLowerCase(Locale.ROOT).contains(query)
                            || entry.name().get().toLowerCase(Locale.ROOT).contains(query)).toList();
            filterDirty = false;
        }
        page = Math.min(page, Math.max(0, (visible.size() - 1) / CONTENT.length));
        for (int index = 0; index < CONTENT.length; index++) {
            int row = page * CONTENT.length + index;
            inventory.setItem(CONTENT[index], row < visible.size() ? visible.get(row).icon().get() : null);
        }
        inventory.setItem(4, icon(new ItemStack(Material.KNOWLEDGE_BOOK), text("title_" + screen.name().toLowerCase(Locale.ROOT)),
                screen == Screen.POT_RECIPES ? (group == null ? text("default_group") : Component.text(group))
                        : screen == Screen.ADDON_RECIPES ? addon.title() : text("choose")));
        inventory.setItem(45, button(Material.BARRIER, back == null ? "close" : "back"));
        if (page > 0) inventory.setItem(46, button(Material.ARROW, "previous"));
        if ((page + 1) * CONTENT.length < visible.size()) inventory.setItem(53, button(Material.ARROW, "next"));
        inventory.setItem(49, icon(new ItemStack(Material.PAPER), text("page", "page", page + 1, "pages", Math.max(1, (visible.size() + CONTENT.length - 1) / CONTENT.length)),
                text("count", "count", visible.size())));
        if (visible.isEmpty()) inventory.setItem(22, button(Material.GLASS_BOTTLE, "empty"));
        if (listScreen() || screen == Screen.POT_GROUPS) {
            inventory.setItem(47, icon(new ItemStack(Material.SPYGLASS), text("search"), query.isEmpty() ? text("search_hint") : Component.text(query)));
            inventory.setItem(50, button(Material.SUNFLOWER, "refresh"));
            if (!query.isEmpty()) inventory.setItem(51, button(Material.MILK_BUCKET, "clear_search"));
            inventory.setItem(52, button(Material.EMERALD, screen == Screen.POT_GROUPS ? "new_group" : "new_recipe"));
        }
        if (screen == Screen.POT_RECIPES) inventory.setItem(48, button(Material.COMPARATOR, "filter_" + List.of("all", "exact", "fuzzy").get(mode)));
    }

    @Override public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (leaving || event.getWhoClicked() != player || !EditorNavigation.allowed(plugin, player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) return;
        if (slot == 45) { leave(back == null ? player::closeInventory : back); return; }
        if (slot == 46 && page > 0) { page--; render(); return; }
        if (slot == 53 && (page + 1) * CONTENT.length < visible.size()) { page++; render(); return; }
        if (slot == 48 && screen == Screen.POT_RECIPES) { mode = (mode + 1) % 3; page = 0; filterDirty = true; render(); return; }
        if (slot == 50 && (listScreen() || screen == Screen.POT_GROUPS)) { loadEntries(); render(); return; }
        if (slot == 51 && !query.isEmpty()) { query = ""; page = 0; filterDirty = true; render(); return; }
        if (slot == 47 && (listScreen() || screen == Screen.POT_GROUPS)) {
            prompt("search_prompt", value -> { if (!value.equalsIgnoreCase("cancel")) { query = value.substring(0, Math.min(128, value.length())).toLowerCase(Locale.ROOT); page = 0; } show(); });
            return;
        }
        if (slot == 52 && screen == Screen.POT_GROUPS) {
            prompt("group_prompt", value -> {
                if (value.equalsIgnoreCase("cancel")) { show(); return; }
                String id = value.toLowerCase(Locale.ROOT);
                if (!RecipeEditorView.isValidRecipeId(id) || plugin.getCookingPotRecipes().getCustomRecipeGroups().containsKey(id)) {
                    player.sendMessage(text("invalid_or_duplicate")); show(); return;
                }
                new RecipeEditorMenuGui(plugin, player, Screen.POT_RECIPES, id, null, this::show).show();
            });
            return;
        }
        if (slot == 52 && listScreen()) {
            if (screen == Screen.POT_RECIPES) leave(() -> new RecipeEditorMenuGui(plugin, player, Screen.CREATE_MODE, group, null, this::show).show());
            else askRecipeId(false);
            return;
        }
        for (int index = 0; index < CONTENT.length; index++) {
            if (CONTENT[index] != slot) continue;
            int row = page * CONTENT.length + index;
            if (row < visible.size()) leave(visible.get(row).action());
            return;
        }
    }

    private void askRecipeId(boolean fuzzy) {
        prompt("id_prompt", value -> {
            if (value.equalsIgnoreCase("cancel")) { show(); return; }
            String id = value.toLowerCase(Locale.ROOT);
            boolean pot = screen == Screen.CREATE_MODE || screen == Screen.POT_RECIPES;
            boolean duplicate = pot ? plugin.getCookingPotRecipes().getEditableRecipes(group).stream().anyMatch(recipe -> recipe.id().equals(id))
                    : addon != null ? addon.recipe(id) != null : plugin.getCuttingBoardRecipes().getRecipe(id) != null;
            if (!RecipeEditorView.isValidRecipeId(id) || addon != null && !id.contains(":") || duplicate) {
                player.sendMessage(text("invalid_or_duplicate")); show(); return;
            }
            Runnable parent = screen == Screen.CREATE_MODE ? back : this::show;
            if (addon != null) RecipeEditorView.open(player, addon, id, parent);
            else RecipeEditorView.create(plugin, player, pot, id, group, fuzzy, parent);
        });
    }

    private void prompt(String key, Consumer<String> callback) {
        leave(() -> {
            player.closeInventory();
            RecipeEditorListener.promptChat(player, value -> {
                if (EditorNavigation.allowed(plugin, player)) callback.accept(value.trim());
            }, "gui.editor.menu." + key);
        });
    }

    private void leave(Runnable action) {
        leaving = true;
        EditorNavigation.next(plugin, player, inventory, action);
    }

    @Override public void handleClose(InventoryCloseEvent event) {
        if (!leaving) EditorNavigation.afterPlayerClose(plugin, player, event, back);
        leaving = true;
    }

    private ItemStack recipeIcon(ItemStack result, String id, boolean fuzzy) {
        ItemStack item = result == null ? new ItemStack(Material.BOOK) : result;
        return icon(item, ItemUtils.getDisplayComponent(item, player), Component.text(id),
                text(screen == Screen.ADDON_RECIPES ? "addon_recipe" : fuzzy ? "mode_fuzzy" : "mode_exact"), text("edit"));
    }

    private Component text(String suffix, Object... args) {
        Map<String, String> placeholders = new java.util.HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) placeholders.put(String.valueOf(args[i]), String.valueOf(args[i + 1]));
        return I18n.getComponent("gui.editor.menu." + suffix, player, placeholders);
    }

    private ItemStack button(Material material, String suffix) { return icon(new ItemStack(material), text(suffix)); }
    private static ItemStack icon(ItemStack source, Component name, Component... lore) {
        ItemStack item = source == null ? new ItemStack(Material.BOOK) : source.clone();
        item.setAmount(1);
        var meta = item.getItemMeta();
        if (meta != null) { meta.displayName(name); meta.lore(List.of(lore)); item.setItemMeta(meta); }
        return item;
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }
}
