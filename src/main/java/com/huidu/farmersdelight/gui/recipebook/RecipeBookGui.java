package com.huidu.farmersdelight.gui.recipebook;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.recipe.RecipeFiller;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
 */
public final class RecipeBookGui implements InventoryHolder {

    enum View { MENU, LIST, DETAIL }

    static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_FILL = 52;

    private View view = View.MENU;
    private RecipeType type;
    private String recipeId;
    private int page;
    private RecipeFiller filler;
    private Inventory inventory;

    public static void openMenu(Player player, RecipeFiller filler) {
        RecipeBookListener.ensureRegistered();
        RecipeBookGui gui = new RecipeBookGui();
        gui.filler = filler;
        gui.drawMenu();
        player.openInventory(gui.inventory);
    }

    public static void openEditor(Player player, RecipeType type, String recipeId) {
        RecipeEditorView.open(player, type, recipeId);
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
        inventory = Bukkit.createInventory(this, 54, Component.text("Recipes", NamedTextColor.DARK_GRAY));
        int slot = 10;
        for (RecipeType registered : FarmersDelightApi.get().recipeTypes()) {
            if (slot >= 44) {
                break;
            }
            ItemStack icon = clone(registered.icon(), Material.BOOK);
            rename(icon, registered.title());
            inventory.setItem(slot, icon);
            slot += (slot % 9 == 7) ? 3 : 1;
        }
        inventory.setItem(SLOT_BACK, button(Material.BARRIER, Component.text("Close", NamedTextColor.RED)));
    }

    void drawList(RecipeType target, int targetPage) {
        view = View.LIST;
        type = target;
        recipeId = null;
        List<ViewableRecipe> recipes = target.recipes();
        int pages = Math.max(1, (recipes.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(targetPage, pages - 1));
        inventory = Bukkit.createInventory(this, 54, target.title());
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < recipes.size(); i++) {
            ViewableRecipe recipe = recipes.get(start + i);
            ItemStack icon = clone(recipe.icon(), Material.PAPER);
            inventory.setItem(i, icon);
        }
        if (page > 0) {
            inventory.setItem(SLOT_PREV, button(Material.ARROW, Component.text("Previous", NamedTextColor.YELLOW)));
        }
        if (page < pages - 1) {
            inventory.setItem(SLOT_NEXT, button(Material.ARROW, Component.text("Next", NamedTextColor.YELLOW)));
        }
        inventory.setItem(SLOT_BACK, button(Material.BARRIER, Component.text("Back", NamedTextColor.RED)));
    }

    void drawDetail(RecipeType target, String id, Player viewer) {
        view = View.DETAIL;
        type = target;
        recipeId = id;
        ViewableRecipe recipe = target.recipe(id);
        inventory = Bukkit.createInventory(this, 54, target.title());
        if (recipe != null) {
            List<ItemStack> inputs = recipe.inputs();
            for (int i = 0; i < inputs.size() && i < 6; i++) {
                inventory.setItem(10 + i, clone(inputs.get(i), Material.AIR));
            }
            ItemStack result = clone(recipe.result(), Material.PAPER);
            List<Component> lore = new ArrayList<>(recipe.infoLines(viewer));
            if (!lore.isEmpty()) {
                applyLore(result, lore);
            }
            inventory.setItem(16, result);
            if (filler != null) {
                inventory.setItem(SLOT_FILL, button(Material.HOPPER,
                        Component.text("Fill ingredients", NamedTextColor.GREEN)));
            }
        }
        inventory.setItem(SLOT_BACK, button(Material.ARROW, Component.text("Back", NamedTextColor.RED)));
    }

    void handleClick(Player player, int rawSlot) {
        switch (view) {
            case MENU -> {
                if (rawSlot == SLOT_BACK) {
                    player.closeInventory();
                    return;
                }
                ItemStack clicked = inventory.getItem(rawSlot);
                if (clicked == null || clicked.getType().isAir()) {
                    return;
                }
                RecipeType target = typeAtMenuSlot(rawSlot);
                if (target != null) {
                    drawList(target, 0);
                    player.openInventory(inventory);
                }
            }
            case LIST -> {
                if (rawSlot == SLOT_BACK) {
                    drawMenu();
                    player.openInventory(inventory);
                } else if (rawSlot == SLOT_PREV) {
                    drawList(type, page - 1);
                    player.openInventory(inventory);
                } else if (rawSlot == SLOT_NEXT) {
                    drawList(type, page + 1);
                    player.openInventory(inventory);
                } else if (rawSlot >= 0 && rawSlot < PAGE_SIZE) {
                    int index = page * PAGE_SIZE + rawSlot;
                    List<ViewableRecipe> recipes = type.recipes();
                    if (index < recipes.size()) {
                        drawDetail(type, recipes.get(index).id(), player);
                        player.openInventory(inventory);
                    }
                }
            }
            case DETAIL -> {
                if (rawSlot == SLOT_BACK) {
                    drawList(type, page);
                    player.openInventory(inventory);
                } else if (rawSlot == SLOT_FILL && filler != null) {
                    ViewableRecipe recipe = type.recipe(recipeId);
                    if (recipe != null && !filler.fill(player, recipe)) {
                        player.sendMessage(Component.text("Missing ingredients to fill.", NamedTextColor.RED));
                    }
                }
            }
        }
    }

    private RecipeType typeAtMenuSlot(int slot) {
        int cursor = 10;
        for (RecipeType registered : FarmersDelightApi.get().recipeTypes()) {
            if (cursor >= 44) {
                break;
            }
            if (cursor == slot) {
                return registered;
            }
            cursor += (cursor % 9 == 7) ? 3 : 1;
        }
        return null;
    }

    static ItemStack clone(ItemStack source, Material fallback) {
        if (source != null && !source.getType().isAir()) {
            return source.clone();
        }
        return new ItemStack(fallback);
    }

    static ItemStack button(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        rename(item, name);
        return item;
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
