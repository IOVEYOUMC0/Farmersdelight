package com.huidu.farmersdelight.recipe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class FuzzyDishFactory {
    private static final NamespacedKey QUALITY = new NamespacedKey("farmersdelight", "dish_quality");
    private FuzzyDishFactory() { }

    static ItemStack create(ItemStack result, FuzzyRecipeMatcher.Match match) {
        ItemStack dish = result.clone();
        dish.setAmount(Math.multiplyExact(result.getAmount(), match.portions()));
        ItemMeta meta = dish.getItemMeta();
        if (meta == null) return dish;
        String quality = match.quality().name().toLowerCase(Locale.ROOT);
        NamedTextColor color = NamedTextColor.NAMES.value(match.quality().color);
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.translatable("gui.fuzzy.quality." + quality).color(color).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(QUALITY, PersistentDataType.STRING, quality);
        if (meta.hasFood()) {
            var food = meta.getFood();
            food.setNutrition(Math.max(1, (int) Math.round(food.getNutrition() * match.quality().foodMultiplier)));
            food.setSaturation((float) (food.getSaturation() * match.quality().foodMultiplier));
            meta.setFood(food);
        }
        dish.setItemMeta(meta);
        return dish;
    }
}
