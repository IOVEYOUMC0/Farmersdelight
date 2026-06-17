package com.huidu.farmersdelight.gui.recipebook;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.recipe.RecipeBookLayout;
import com.huidu.farmersdelight.api.recipe.RecipeFiller;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
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
import java.util.Map;
import java.util.Set;

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

    /** Opens directly to a single type's own list+detail (no shared category menu). */
    public static void openType(Player player, RecipeType type, RecipeFiller filler) {
        RecipeBookListener.ensureRegistered();
        RecipeBookGui gui = new RecipeBookGui();
        gui.filler = filler;
        gui.singleType = true;
        gui.drawList(type, 0);
        player.openInventory(gui.inventory);
    }

    // Roles whose slots are filled dynamically/conditionally by the GUI (not static chrome).
    private static final Set<String> DYNAMIC_ROLES = Set.of(
            "category", "recipe", "ingredient", "result", "prev_page", "next_page", "fill");

    /** A page's renderable spec — backed either by the shared RecipeBookGuiConfig.ViewConfig or by a
     * type's own RecipeBookLayout. Lets list/detail/click logic stay layout-source-agnostic. */
    private interface RenderSpec {
        int size();
        Component title();
        void renderChrome(Inventory inventory);
        List<Integer> slotsByType(String role);
        int firstSlotByType(String role);
        ItemStack button(String role);
    }

    private record ViewConfigSpec(RecipeBookGuiConfig.ViewConfig cfg, Component title) implements RenderSpec {
        public int size() {
            return cfg.size();
        }
        public void renderChrome(Inventory inventory) {
            cfg.renderChrome(inventory);
        }
        public List<Integer> slotsByType(String role) {
            return cfg.slotsByType(role);
        }
        public int firstSlotByType(String role) {
            return cfg.firstSlotByType(role);
        }
        public ItemStack button(String role) {
            GuiConfig.GuiItem item = cfg.item(role);
            return item == null ? null : item.createItem();
        }
    }

    private record LayoutSpec(RecipeBookLayout layout) implements RenderSpec {
        public int size() {
            return layout.size();
        }
        public Component title() {
            return layout.title();
        }
        public void renderChrome(Inventory inventory) {
            List<String> rows = layout.layout();
            Map<Character, String> legend = layout.legend();
            Map<String, ItemStack> decorations = layout.decorations();
            for (int row = 0; row < rows.size(); row++) {
                String line = rows.get(row);
                for (int col = 0; col < line.length() && col < 9; col++) {
                    int slot = row * 9 + col;
                    if (slot >= inventory.getSize()) {
                        continue;
                    }
                    String role = legend.get(line.charAt(col));
                    if (role == null || DYNAMIC_ROLES.contains(role)) {
                        continue;
                    }
                    ItemStack item = decorations.get(role);
                    if (item != null) {
                        inventory.setItem(slot, item.clone());
                    }
                }
            }
        }
        public List<Integer> slotsByType(String role) {
            return layout.slotsByType(role);
        }
        public int firstSlotByType(String role) {
            return layout.firstSlotByType(role);
        }
        public ItemStack button(String role) {
            ItemStack item = layout.decorations().get(role);
            return item == null ? null : item.clone();
        }
    }

    private RenderSpec listSpec(RecipeType target) {
        RecipeBookLayout layout = target.listLayout();
        return layout != null ? new LayoutSpec(layout) : new ViewConfigSpec(config().list(), target.title());
    }

    private RenderSpec detailSpec(RecipeType target) {
        RecipeBookLayout layout = target.detailLayout();
        return layout != null ? new LayoutSpec(layout) : new ViewConfigSpec(config().detail(), target.title());
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
        RenderSpec spec = listSpec(target);
        List<Integer> recipeSlots = spec.slotsByType("recipe");
        int pageSize = Math.max(1, recipeSlots.size());
        List<ViewableRecipe> recipes = target.recipes();
        int pages = Math.max(1, (recipes.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(targetPage, pages - 1));
        inventory = Bukkit.createInventory(this, spec.size(), spec.title());
        spec.renderChrome(inventory);
        int start = page * pageSize;
        for (int i = 0; i < recipeSlots.size() && start + i < recipes.size(); i++) {
            inventory.setItem(recipeSlots.get(i), clone(recipes.get(start + i).icon(), Material.PAPER));
        }
        if (page > 0) {
            placeButton(spec, "prev_page");
        }
        if (page < pages - 1) {
            placeButton(spec, "next_page");
        }
    }

    void drawDetail(RecipeType target, String id, Player viewer) {
        view = View.DETAIL;
        type = target;
        recipeId = id;
        RenderSpec spec = detailSpec(target);
        ViewableRecipe recipe = target.recipe(id);
        inventory = Bukkit.createInventory(this, spec.size(), spec.title());
        spec.renderChrome(inventory);
        if (recipe != null) {
            List<Integer> ingredientSlots = spec.slotsByType("ingredient");
            List<ItemStack> inputs = recipe.inputs();
            for (int i = 0; i < ingredientSlots.size() && i < inputs.size(); i++) {
                inventory.setItem(ingredientSlots.get(i), clone(inputs.get(i), Material.AIR));
            }
            // Custom roles supplied by the recipe (e.g. fluid / return / temperature for the keg), placed
            // into the type's own detail layout slots for that role.
            for (Map.Entry<String, List<ItemStack>> entry : recipe.displaySlots().entrySet()) {
                List<Integer> slots = spec.slotsByType(entry.getKey());
                List<ItemStack> items = entry.getValue();
                for (int i = 0; i < slots.size() && i < items.size(); i++) {
                    if (items.get(i) != null && !items.get(i).getType().isAir()) {
                        inventory.setItem(slots.get(i), items.get(i).clone());
                    }
                }
            }
            int resultSlot = spec.firstSlotByType("result");
            if (resultSlot >= 0) {
                ItemStack result = clone(recipe.result(), Material.PAPER);
                List<Component> lore = new ArrayList<>(recipe.infoLines(viewer));
                if (!lore.isEmpty()) {
                    applyLore(result, lore);
                }
                inventory.setItem(resultSlot, result);
            }
            if (filler != null) {
                placeButton(spec, "fill");
            }
        }
    }

    private void placeButton(RenderSpec spec, String role) {
        int slot = spec.firstSlotByType(role);
        ItemStack item = spec.button(role);
        if (slot >= 0 && item != null) {
            inventory.setItem(slot, item);
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
                RenderSpec cfg = listSpec(type);
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
                RenderSpec cfg = detailSpec(type);
                if (rawSlot == cfg.firstSlotByType("back")) {
                    drawList(type, page);
                    player.openInventory(inventory);
                } else if (rawSlot == cfg.firstSlotByType("fill") && filler != null) {
                    ViewableRecipe recipe = type.recipe(recipeId);
                    if (recipe != null && !filler.fill(player, recipe)) {
                        player.sendMessage(Text.deserialize(I18n.get("gui.recipe.missing_ingredients", player)));
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
