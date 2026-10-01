package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.FoodGroupSnapshot;
import com.huidu.farmersdelight.recipe.FuzzyRecipeSpec;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Local food-group editor. Player inventory clicks copy identities without taking real items. */
public final class FoodGroupEditorGui implements EditorGui {
    private final FarmersDelightPlugin plugin;
    private final Player player;
    private final Runnable back;
    private final Inventory inventory;
    private final String originalId;
    private final List<String> items = new ArrayList<>();
    private final boolean listing;
    private String id;
    private FoodGroupSnapshot.Kind kind = FoodGroupSnapshot.Kind.EQUIVALENT;
    private List<FoodGroupSnapshot.Group> listed = List.of();
    private int page;
    private boolean saving;
    private boolean deleteArmed;
    private boolean leaving;

    private FoodGroupEditorGui(FarmersDelightPlugin plugin, Player player, FoodGroupSnapshot.Group group,
                                boolean listing, Runnable back) {
        this.plugin = plugin;
        this.player = player;
        this.back = back;
        this.listing = listing;
        this.originalId = group == null ? null : group.id();
        this.id = group == null ? "farmersdelight:group_" + Long.toString(System.nanoTime(), 36) : group.id();
        if (group != null) { this.kind = group.kind(); this.items.addAll(group.items()); }
        inventory = plugin.getServer().createInventory(this, 54, I18n.getComponent("gui.fuzzy.groups.title", player));
    }

    public static void open(FarmersDelightPlugin plugin, Player player, Runnable back) {
        new FoodGroupEditorGui(plugin, player, null, true, back).show();
    }

    public static void open(FarmersDelightPlugin plugin, Player player, String id, Runnable back) {
        FoodGroupSnapshot.Group group = plugin.getCookingPotRecipes().getLocalFoodGroups().stream()
                .filter(value -> value.id().equals(id)).findFirst().orElse(null);
        FoodGroupEditorGui editor = new FoodGroupEditorGui(plugin, player, group, false, back);
        if (group == null) editor.id = FuzzyRecipeSpec.normalizeId(id);
        editor.show();
    }

    private void show() {
        if (!EditorNavigation.allowed(plugin, player)) return;
        RecipeEditorListener.ensureRegistered(plugin);
        RecipeEditorListener.cancelPrompt(player.getUniqueId());
        leaving = false;
        render();
        player.openInventory(inventory);
    }

    private void render() {
        inventory.clear();
        if (listing) {
            listed = plugin.getCookingPotRecipes().getLocalFoodGroups();
            page = Math.min(page, Math.max(0, (listed.size() - 1) / 45));
            for (int slot = 0; slot < 45; slot++) {
                int index = page * 45 + slot;
                if (index >= listed.size()) break;
                var group = listed.get(index);
                inventory.setItem(slot, icon(Material.NAME_TAG, Component.text(group.id()),
                        I18n.getComponent("gui.fuzzy.groups.kind_" + group.kind().name().toLowerCase(java.util.Locale.ROOT), player),
                        I18n.getComponent("gui.fuzzy.groups.members", player, Map.of("count", String.valueOf(group.items().size())))));
            }
            inventory.setItem(49, button(Material.EMERALD, "new"));
            inventory.setItem(48, button(Material.BARRIER, "back"));
        } else {
            page = Math.min(page, Math.max(0, (items.size() - 1) / 36));
            for (int slot = 0; slot < 36; slot++) {
                int index = page * 36 + slot;
                if (index >= items.size()) break;
                ItemStack item = ItemUtils.createItem(items.get(index));
                inventory.setItem(slot, icon(item == null ? new ItemStack(Material.BARRIER) : item.clone(),
                        Component.text(items.get(index)), I18n.getComponent("gui.fuzzy.groups.remove", player)));
            }
            inventory.setItem(36, icon(Material.NAME_TAG, Component.text(id), I18n.getComponent("gui.fuzzy.groups.rename", player)));
            inventory.setItem(38, button(Material.COMPARATOR, "kind_" + kind.name().toLowerCase(java.util.Locale.ROOT)));
            inventory.setItem(40, button(Material.LIME_CONCRETE, "save"));
            inventory.setItem(42, button(Material.LAVA_BUCKET, deleteArmed ? "delete_confirm" : "delete"));
            inventory.setItem(44, button(Material.BARRIER, "back"));
            inventory.setItem(49, button(Material.CHEST, "add_hint"));
        }
        inventory.setItem(45, button(Material.ARROW, "previous"));
        inventory.setItem(53, button(Material.ARROW, "next"));
    }

    @Override public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (saving || leaving || event.getWhoClicked() != player || !EditorNavigation.allowed(plugin, player)) return;
        int slot = event.getRawSlot();
        if (event.getClickedInventory() == player.getInventory()) {
            if (listing) return;
            ItemStack item = event.getCurrentItem();
            if (item == null || item.getType().isAir()) return;
            try {
                String member = FuzzyRecipeSpec.normalizeId(ItemUtils.resolveItemId(item));
                if (!items.contains(member) && items.size() < 4096) items.add(member);
                page = (items.size() - 1) / 36;
                render();
            } catch (IllegalArgumentException invalid) { message("invalid"); }
            return;
        }
        if (slot < 0 || slot >= inventory.getSize()) return;
        if (slot == 45) { page = Math.max(0, page - 1); render(); return; }
        if (slot == 53) { page++; render(); return; }
        if (listing) {
            int index = page * 45 + slot;
            if (slot < 45 && index < listed.size()) {
                leave(() -> new FoodGroupEditorGui(plugin, player, listed.get(index), false, this::show).show());
            } else if (slot == 49) leave(() -> new FoodGroupEditorGui(plugin, player, null, false, this::show).show());
            else if (slot == 48) leave(back);
            return;
        }
        if (slot < 36) {
            int index = page * 36 + slot;
            if (index < items.size()) items.remove(index);
            render();
        } else if (slot == 36) {
            leave(() -> {
                player.closeInventory();
                RecipeEditorListener.promptChat(player, value -> {
                    if (!EditorNavigation.allowed(plugin, player)) return;
                    if (!"cancel".equalsIgnoreCase(value.trim())) {
                        try { id = FuzzyRecipeSpec.normalizeId(value); }
                        catch (IllegalArgumentException invalid) { message("invalid"); }
                    }
                    show();
                }, "gui.fuzzy.groups.id_prompt");
            });
        } else if (slot == 38) {
            kind = kind == FoodGroupSnapshot.Kind.EQUIVALENT ? FoodGroupSnapshot.Kind.SEASONING : FoodGroupSnapshot.Kind.EQUIVALENT;
            render();
        } else if (slot == 40) save();
        else if (slot == 42 && originalId != null) {
            if (deleteArmed) finish(RecipeEditorView.store().deleteFoodGroupAsync(originalId));
            else { deleteArmed = true; render(); }
        } else if (slot == 44) leave(back);
    }

    private void save() {
        if (items.isEmpty()) { message("empty"); return; }
        if (!id.equals(originalId) && plugin.getCookingPotRecipes().getLocalFoodGroups().stream().anyMatch(group -> group.id().equals(id))) {
            message("duplicate"); return;
        }
        finish(RecipeEditorView.store().saveFoodGroupAsync(originalId, new FoodGroupSnapshot.Group(id, kind, items)));
    }

    private void finish(CompletableFuture<Boolean> future) {
        saving = true;
        future.whenComplete((success, error) -> {
            if (!plugin.isEnabled()) return;
            plugin.scheduler().runForEntity(player, () -> {
                saving = false;
                if (!player.isOnline()) return;
                boolean saved = error == null && Boolean.TRUE.equals(success);
                message(saved ? "saved" : "failed");
                if (saved && player.getOpenInventory().getTopInventory() == inventory) leave(back);
            });
            if (error != null) plugin.getLogger().log(java.util.logging.Level.WARNING, "Food group editor publication failed", error);
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

    private void message(String suffix) { player.sendMessage(I18n.getComponent("gui.fuzzy.groups." + suffix, player)); }
    private ItemStack button(Material material, String suffix) { return icon(material, I18n.getComponent("gui.fuzzy.groups." + suffix, player)); }
    private static ItemStack icon(Material material, Component name, Component... lore) { return icon(new ItemStack(material), name, lore); }
    private static ItemStack icon(ItemStack item, Component name, Component... lore) {
        item.setAmount(1);
        var meta = item.getItemMeta();
        if (meta != null) { meta.displayName(name); meta.lore(List.of(lore)); item.setItemMeta(meta); }
        return item;
    }
    @Override public @NotNull Inventory getInventory() { return inventory; }
}
