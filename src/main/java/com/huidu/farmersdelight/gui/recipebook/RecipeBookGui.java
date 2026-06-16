package com.huidu.farmersdelight.gui.recipebook;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.recipe.RecipeFiller;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic, registrable recipe book: a category menu over all registered RecipeTypes, a paginated
 * recipe list per category, and a recipe detail view. Type-agnostic — it only consumes the api
 * abstractions, so it never touches FarmersDelight's own recipe types or the legacy RecipeViewGui.
 *
 * <p>Layout/title/buttons are config-driven via gui.yml -> recipe-book-gui (see
 * RecipeBookGuiConfig); the list page size follows the number of recipe slots.
 */
public final class RecipeBookGui implements InventoryHolder {

    enum View { MENU, LIST, DETAIL }

    private static volatile RecipeBookGuiConfig cachedConfig;

    private View view = View.MENU;
    private RecipeType type;
    private String recipeId;
    private int page;
    private RecipeFiller filler;
    private Inventory inventory;
    // When only one recipe type is registered, skip the category chooser and open its list directly.
    private boolean singleType;

    public static void openMenu(Player player, RecipeFiller filler) {
        RecipeBookListener.ensureRegistered();
        RecipeBookGui gui = new RecipeBookGui();
        gui.filler = filler;
        List<RecipeType> types = FarmersDelightApi.get().recipeTypes();
        if (types.size() == 1) {
            gui.singleType = true;
            gui.drawList(types.get(0), 0);
        } else {
            gui.drawMenu();
        }
        player.openInventory(gui.inventory);
    }

    public static void openEditor(Player player, RecipeType type, String recipeId) {
        RecipeEditorView.open(player, type, recipeId);
    }

    /** Drops the cached config so the next open re-reads gui.yml (called on /fd reload gui). */
    public static void clearConfigCache() {
        cachedConfig = null;
    }

    private static RecipeBookGuiConfig config() {
        RecipeBookGuiConfig cached = cachedConfig;
        if (cached != null) {
            return cached;
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        ConfigurationSection section = plugin != null ? plugin.getRecipeBookGuiSection() : null;
        cached = RecipeBookGuiConfig.fromConfig(section);
        cachedConfig = cached;
        return cached;
    }

    View view() {
        return view;
    }

    RecipeType type() {
        return type;
    }

    int page() {
        return page;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    void drawMenu() {
        view = View.MENU;
        type = null;
        recipeId = null;
        RecipeBookGuiConfig.ViewConfig cfg = config().menu();
        inventory = Bukkit.createInventory(this, cfg.size(), Text.title(cfg.title()));
        cfg.renderChrome(inventory);
        List<Integer> categorySlots = cfg.slotsByType("category");
        List<RecipeType> types = FarmersDelightApi.get().recipeTypes();
        for (int i = 0; i < categorySlots.size() && i < types.size(); i++) {
            RecipeType registered = types.get(i);
            ItemStack icon = clone(registered.icon(), Material.BOOK);
            rename(icon, registered.title());
            inventory.setItem(categorySlots.get(i), icon);
        }
    }

    void drawList(RecipeType target, int targetPage) {
        view = View.LIST;
        type = target;
        recipeId = null;
        RecipeBookGuiConfig.ViewConfig cfg = config().list();
        List<Integer> recipeSlots = cfg.slotsByType("recipe");
        int pageSize = Math.max(1, recipeSlots.size());
        List<ViewableRecipe> recipes = target.recipes();
        int pages = Math.max(1, (recipes.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(targetPage, pages - 1));
        inventory = Bukkit.createInventory(this, cfg.size(), target.title());
        cfg.renderChrome(inventory);
        int start = page * pageSize;
        for (int i = 0; i < recipeSlots.size() && start + i < recipes.size(); i++) {
            inventory.setItem(recipeSlots.get(i), clone(recipes.get(start + i).icon(), Material.PAPER));
        }
        if (page > 0) {
            placeButton(cfg, "prev_page");
        }
        if (page < pages - 1) {
            placeButton(cfg, "next_page");
        }
    }

    void drawDetail(RecipeType target, String id, Player viewer) {
        view = View.DETAIL;
        type = target;
        recipeId = id;
        RecipeBookGuiConfig.ViewConfig cfg = config().detail();
        ViewableRecipe recipe = target.recipe(id);
        inventory = Bukkit.createInventory(this, cfg.size(), target.title());
        cfg.renderChrome(inventory);
        if (recipe != null) {
            List<Integer> ingredientSlots = cfg.slotsByType("ingredient");
            List<ItemStack> inputs = recipe.inputs();
            for (int i = 0; i < ingredientSlots.size() && i < inputs.size(); i++) {
                inventory.setItem(ingredientSlots.get(i), clone(inputs.get(i), Material.AIR));
            }
            int resultSlot = cfg.firstSlotByType("result");
            if (resultSlot >= 0) {
                ItemStack result = clone(recipe.result(), Material.PAPER);
                List<Component> lore = new ArrayList<>(recipe.infoLines(viewer));
                if (!lore.isEmpty()) {
                    applyLore(result, lore);
                }
                inventory.setItem(resultSlot, result);
            }
            if (filler != null) {
                placeButton(cfg, "fill");
            }
        }
    }

    private void placeButton(RecipeBookGuiConfig.ViewConfig cfg, String type) {
        int slot = cfg.firstSlotByType(type);
        var item = cfg.item(type);
        if (slot >= 0 && item != null) {
            inventory.setItem(slot, item.createItem());
        }
    }

    void handleClick(Player player, int rawSlot) {
        RecipeBookGuiConfig config = config();
        switch (view) {
            case MENU -> {
                RecipeBookGuiConfig.ViewConfig cfg = config.menu();
                if (rawSlot == cfg.firstSlotByType("back")) {
                    player.closeInventory();
                    return;
                }
                int index = cfg.slotsByType("category").indexOf(rawSlot);
                if (index < 0) {
                    return;
                }
                List<RecipeType> types = FarmersDelightApi.get().recipeTypes();
                if (index < types.size()) {
                    drawList(types.get(index), 0);
                    player.openInventory(inventory);
                }
            }
            case LIST -> {
                RecipeBookGuiConfig.ViewConfig cfg = config.list();
                if (rawSlot == cfg.firstSlotByType("back")) {
                    if (singleType) {
                        player.closeInventory();
                    } else {
                        drawMenu();
                        player.openInventory(inventory);
                    }
                } else if (rawSlot == cfg.firstSlotByType("prev_page")) {
                    drawList(type, page - 1);
                    player.openInventory(inventory);
                } else if (rawSlot == cfg.firstSlotByType("next_page")) {
                    drawList(type, page + 1);
                    player.openInventory(inventory);
                } else {
                    List<Integer> recipeSlots = cfg.slotsByType("recipe");
                    int slotIndex = recipeSlots.indexOf(rawSlot);
                    if (slotIndex >= 0) {
                        int index = page * Math.max(1, recipeSlots.size()) + slotIndex;
                        List<ViewableRecipe> recipes = type.recipes();
                        if (index < recipes.size()) {
                            drawDetail(type, recipes.get(index).id(), player);
                            player.openInventory(inventory);
                        }
                    }
                }
            }
            case DETAIL -> {
                RecipeBookGuiConfig.ViewConfig cfg = config.detail();
                if (rawSlot == cfg.firstSlotByType("back")) {
                    drawList(type, page);
                    player.openInventory(inventory);
                } else if (rawSlot == cfg.firstSlotByType("fill") && filler != null) {
                    ViewableRecipe recipe = type.recipe(recipeId);
                    if (recipe != null && !filler.fill(player, recipe)) {
                        player.sendMessage(Component.text("Missing ingredients to fill.", NamedTextColor.RED));
                    }
                }
            }
        }
    }

    static ItemStack clone(ItemStack source, Material fallback) {
        if (source != null && !source.getType().isAir()) {
            return source.clone();
        }
        return new ItemStack(fallback);
    }

    static void rename(ItemStack item, Component name) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && name != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
    }

    static void applyLore(ItemStack item, List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> formatted = new ArrayList<>();
            for (Component line : lore) {
                formatted.add(line.decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(formatted);
            item.setItemMeta(meta);
        }
    }
}
