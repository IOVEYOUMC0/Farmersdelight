package com.huidu.farmersdelight.recipe;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.BiPredicate;

/** Detached transfer planning. A failed plan never mutates either input inventory. */
public final class RecipeAutoFillPlan {
    public enum Result { FILLED, ALREADY_FILLED, MISSING_ITEMS, NO_SPACE, OTHER_INGREDIENTS, INVALID_RECIPE, WRONG_REGION }
    public record Plan(Result result, ItemStack[] playerItems, ItemStack[] potItems) { }
    private RecipeAutoFillPlan() { }

    public static Plan prepare(List<String> requested, ItemStack[] playerItems, ItemStack[] potItems,
                                boolean creative, Function<ItemStack, String> itemId) {
        return prepare(requested, playerItems, potItems, creative, itemId, String::equals);
    }

    public static Plan prepare(List<String> requested, ItemStack[] playerItems, ItemStack[] potItems,
                                boolean creative, Function<ItemStack, String> itemId, BiPredicate<String, String> matches) {
        if (requested == null || requested.isEmpty() || requested.size() > 54) return failed(Result.INVALID_RECIPE);
        try { for (String id : requested) FuzzyRecipeSpec.normalizeId(id); }
        catch (IllegalArgumentException invalid) { return failed(Result.INVALID_RECIPE); }
        if (requested.size() > potItems.length) return failed(Result.NO_SPACE);
        ItemStack[] source = copy(playerItems);
        ItemStack[] target = copy(potItems);
        String[] sourceIds = new String[source.length];
        String[] targetIds = new String[target.length];
        for (int i = 0; i < source.length; i++) if (!empty(source[i])) sourceIds[i] = itemId.apply(source[i]);
        for (int i = 0; i < target.length; i++) if (!empty(target[i])) targetIds[i] = itemId.apply(target[i]);
        boolean[] claimed = new boolean[target.length];
        List<String> missing = new ArrayList<>();
        for (String id : requested) {
            int present = -1;
            for (int i = 0; i < target.length; i++) {
                if (!claimed[i] && targetIds[i] != null && matches.test(id, targetIds[i])) { present = i; break; }
            }
            if (present >= 0) claimed[present] = true;
            else missing.add(id);
        }
        for (int i = 0; i < target.length; i++) {
            if (!empty(target[i]) && !claimed[i]) return failed(Result.OTHER_INGREDIENTS);
        }
        if (missing.isEmpty()) return failed(Result.ALREADY_FILLED);
        for (String id : missing) {
            int playerSlot = -1;
            for (int i = 0; i < source.length; i++) {
                if (!empty(source[i]) && sourceIds[i] != null && matches.test(id, sourceIds[i])) { playerSlot = i; break; }
            }
            if (playerSlot < 0) return failed(Result.MISSING_ITEMS);
            int potSlot = -1;
            for (int i = 0; i < target.length; i++) if (empty(target[i])) { potSlot = i; break; }
            if (potSlot < 0) return failed(Result.NO_SPACE);
            ItemStack unit = source[playerSlot].clone();
            unit.setAmount(1);
            target[potSlot] = unit;
            // Creative players must possess every requested unit too; only the committed debit is skipped.
            source[playerSlot].setAmount(source[playerSlot].getAmount() - 1);
            if (source[playerSlot].getAmount() <= 0) source[playerSlot] = null;
        }
        return new Plan(Result.FILLED, creative ? copy(playerItems) : source, target);
    }

    private static Plan failed(Result result) { return new Plan(result, null, null); }
    private static boolean empty(ItemStack item) {
        if (item == null || item.getAmount() <= 0) return true;
        org.bukkit.Material type = item.getType();
        return type == org.bukkit.Material.AIR || type == org.bukkit.Material.CAVE_AIR || type == org.bukkit.Material.VOID_AIR;
    }
    public static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = empty(items[i]) ? null : items[i].clone();
        return copy;
    }
}
