package com.huidu.farmersdelight.api.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Builds a GUI decoration item from the {@code items:} section shape FarmersDelight's own GUIs use.
 *
 * <p>Recognised keys: {@code item} (a CraftEngine item id), {@code material}, {@code custom-model-data},
 * {@code item-model}, {@code hide-tooltip}, {@code name} / {@code lore} (MiniMessage, glyph tags
 * resolved), and {@code name-key} / {@code lore-keys} (server-side translation keys).
 *
 * <p>The last pair is the reason to use this rather than a private copy: every addon that hand-rolled
 * this builder supported only literal {@code name}/{@code lore}, so their GUI decorations could not be
 * translated while the rest of the family could.
 */
public final class GuiItems {

    private GuiItems() {
    }

    /** Builds the item, or null when the section is null. */
    public static ItemStack build(ConfigurationSection section) {
        return section == null
                ? null
                : com.huidu.farmersdelight.gui.GuiConfig.GuiItem.fromConfig(section).createItem();
    }

    /**
     * Builds the item with {@code {placeholder}} substitutions applied to its name and lore, for the
     * decorations whose text carries runtime values (page numbers, counts).
     */
    public static ItemStack build(ConfigurationSection section, Map<String, String> placeholders) {
        return section == null
                ? null
                : com.huidu.farmersdelight.gui.GuiConfig.GuiItem.fromConfig(section).createItem(placeholders);
    }
}
