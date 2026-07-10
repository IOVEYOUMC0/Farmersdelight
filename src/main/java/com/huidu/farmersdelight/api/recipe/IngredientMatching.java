package com.huidu.farmersdelight.api.recipe;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

/**
 * Generic two-pass recipe-ingredient matcher. Decoupled from Bukkit so the matching logic is unit-testable
 * without a running server: callers supply a {@code matcher} predicate (does this slot satisfy this ingredient?)
 * and an {@code initialAmount} function (how many ingredient-units the slot contributes).
 *
 * <p>Used by the cooking pot and the keg, and exposed to addons that need to mirror the same matching semantics.
 *
 * <p>Pass semantics:
 * <ul>
 *   <li><b>exactSlots = true</b> — slot count must equal required-ingredient count (no leftover slots). The
 *       cooking pot tries this first so e.g. beetroot soup in 4 beetroot slots wins before any lenient recipe
 *       can shadow it.</li>
 *   <li><b>exactSlots = false</b> — extra filled slots are allowed only when each one holds an item the recipe
 *       itself uses (the same ingredient spread over several slots — rice in 3 slots for a 1-rice recipe). A
 *       slot holding a foreign item the recipe can't use still blocks the match, so unrelated recipes can't
 *       interfere by sneaking in extra ingredients.</li>
 * </ul>
 */
public final class IngredientMatching {

    private IngredientMatching() {
    }

    public static <Slot, Ingredient> boolean matchesIngredients(
            List<Ingredient> required,
            List<Slot> slots,
            boolean exactSlots,
            BiPredicate<Slot, Ingredient> matcher,
            ToIntFunction<Slot> initialAmount) {
        int n = slots.size();
        int requiredCount = required.size();

        if (exactSlots) {
            if (requiredCount != n) {
                return false;
            }
        } else {
            if (n < requiredCount) {
                return false;
            }
            for (Slot slot : slots) {
                boolean usable = false;
                for (Ingredient ingredient : required) {
                    if (matcher.test(slot, ingredient)) {
                        usable = true;
                        break;
                    }
                }
                if (!usable) {
                    return false;
                }
            }
        }

        int[] remaining = new int[n];
        for (int i = 0; i < n; i++) {
            remaining[i] = Math.max(0, initialAmount.applyAsInt(slots.get(i)));
        }
        for (Ingredient ingredient : required) {
            boolean found = false;
            for (int i = 0; i < n; i++) {
                if (remaining[i] > 0 && matcher.test(slots.get(i), ingredient)) {
                    remaining[i]--;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
