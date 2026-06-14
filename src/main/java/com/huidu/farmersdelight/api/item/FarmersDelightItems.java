package com.huidu.farmersdelight.api.item;

import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Stable, addon-facing item helpers for FarmersDelight. Resolves and matches items across CraftEngine
 * custom items, vanilla materials, and item tags (custom + vanilla).
 *
 * <p>This class lives in com.huidu.farmersdelight.api.**, the only package kept name-stable
 * through obfuscation, so addons may call it directly. Signatures use only Bukkit / java types.
 */
public final class FarmersDelightItems {

    private FarmersDelightItems() {
    }

    /** CraftEngine custom id, else minecraft:<material>, else null for empty/air. */
    public static String idOf(ItemStack item) {
        return ItemUtils.resolveItemId(item);
    }

    /** Builds a stack from a namespaced id (CraftEngine custom item or vanilla material), or null. */
    public static ItemStack create(String itemId) {
        return ItemUtils.createItem(itemId);
    }

    /** True if item resolves to the given namespaced id (CraftEngine or vanilla). */
    public static boolean matchesId(ItemStack item, String itemId) {
        return ItemUtils.matchesItemId(item, itemId);
    }

    /** True if item carries the given tag id (#ns:tag or ns:tag); matches both CraftEngine custom and vanilla tags. */
    public static boolean matchesTag(ItemStack item, String tagId) {
        return ItemUtils.matchesCustomOrVanillaTag(item, tagId);
    }

    /** The item's display name component, localized for player (player may be null). */
    public static Component displayNameOf(ItemStack item, Player player) {
        return ItemUtils.getDisplayComponent(item, player);
    }

    /** All namespaced ids the item resolves to (custom id and/or vanilla material). */
    public static Set<String> idsOf(ItemStack item) {
        return ItemUtils.getItemIds(item);
    }

    /** All tag ids the item carries (CraftEngine custom tags + vanilla tags). */
    public static Set<String> tagIdsOf(ItemStack item) {
        return ItemUtils.getItemTagIds(item);
    }
}
