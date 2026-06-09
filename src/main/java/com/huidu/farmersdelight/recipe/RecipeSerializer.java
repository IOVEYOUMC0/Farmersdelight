package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Converts in-memory recipe models back into the YAML string forms understood by the recipe parsers
 * (the inverse of {@link RecipeParsingSupport} / the manager {@code parseRecipe} methods).
 *
 * <p>The string conversions here are pure (no Bukkit/CraftEngine state) so they can be unit-tested
 * for round-trip fidelity. Item resolution ({@link #itemIdString(ItemStack)}) is the one method that
 * touches CraftEngine, kept separate for that reason.
 */
public final class RecipeSerializer {

    private RecipeSerializer() {
    }

    /**
     * Serializes an ingredient (cooking-pot ingredient slot, or cutting-board input) to its YAML
     * string: an item key, a {@code #tag} with optional {@code ,!exclusions}, or {@code a|b} choices.
     */
    public static String serializeIngredient(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            return item.key().toString();
        }
        if (ingredient instanceof RecipeIngredient.Tag tag) {
            return serializeTag(tag.key(), tag.excludedItems(), tag.excludedTags());
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            StringBuilder builder = new StringBuilder();
            List<RecipeIngredient> options = choice.options();
            for (int i = 0; i < options.size(); i++) {
                if (i > 0) {
                    builder.append('|');
                }
                builder.append(serializeIngredient(options.get(i)));
            }
            return builder.toString();
        }
        return "";
    }

    /**
     * Serializes a cutting-board tool requirement. The tool key is matched as an item id or a tag
     * indifferently, so it is emitted as a raw key plus any {@code ,!exclusions}.
     */
    public static String serializeTool(CuttingBoardRecipe.ToolRequirement tool) {
        return serializeKeyWithExclusions(tool.getKey().toString(), tool.getExcludedItems(), tool.getExcludedTags());
    }

    private static String serializeTag(Key key, Set<Key> excludedItems, Set<Key> excludedTags) {
        return serializeKeyWithExclusions("#" + key, excludedItems, excludedTags);
    }

    private static String serializeKeyWithExclusions(String base, Set<Key> excludedItems, Set<Key> excludedTags) {
        StringBuilder builder = new StringBuilder(base);
        // Sort exclusions so the serialized form is deterministic (sets are unordered).
        List<String> exclusions = new ArrayList<>();
        for (Key excludedItem : excludedItems) {
            exclusions.add(",!" + excludedItem);
        }
        for (Key excludedTag : excludedTags) {
            exclusions.add(",!#" + excludedTag);
        }
        Collections.sort(exclusions);
        for (String exclusion : exclusions) {
            builder.append(exclusion);
        }
        return builder.toString();
    }

    /**
     * Resolves an item stack to a recipe item id string: its CraftEngine custom id when present,
     * otherwise {@code minecraft:<material>}.
     */
    public static String itemIdString(ItemStack item) {
        if (item == null) {
            return null;
        }
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        return "minecraft:" + item.getType().name().toLowerCase(Locale.ROOT);
    }
}
