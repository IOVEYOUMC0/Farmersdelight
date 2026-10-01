package com.huidu.farmersdelight.recipe;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecipeParseCacheTest {
    private YamlConfiguration document(String text) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test void equalDocumentsReuseTheParsedObjectWithoutDecodingItemsAgain() throws Exception {
        var first = new RecipeParseCache<Object>(null);
        Object recipe = new Object();
        first.parse("file", "soup", document("time: 20\nitems: [apple, null]\n"), () -> recipe);
        var next = new RecipeParseCache<>(first);
        assertSame(recipe, next.parse("file", "soup", document("items: [apple, null]\ntime: 20\n"), () -> {
            fail("Unchanged items must not be decoded"); return null;
        }));
        assertEquals(1, next.reused());
        assertEquals(0, next.parsed());
    }

    @Test void changedEntriesAreReparsedAndRemovedEntriesAreEvicted() throws Exception {
        var old = new RecipeParseCache<String>(null);
        old.parse("file", "soup", document("time: 20\n"), () -> "old");
        old.parse("file", "deleted", document("time: 20\n"), () -> "deleted");
        var changed = new RecipeParseCache<>(old);
        assertEquals("new", changed.parse("file", "soup", document("time: 21\n"), () -> "new"));
        var next = new RecipeParseCache<>(changed);
        assertEquals("reintroduced", next.parse("file", "deleted", document("time: 20\n"), () -> "reintroduced"));
        assertEquals(1, next.parsed());
    }

    @Test void sourceAndGroupIdentityKeepIdenticalBodiesSeparate() throws Exception {
        var yaml = document("time: 20\n");
        var old = new RecipeParseCache<String>(null);
        old.parse("file", "a/b", yaml, () -> "default");
        old.parse("file", List.of("custom", "a", "b"), yaml, () -> "custom");
        old.parse("pack", "a/b", yaml, () -> "pack");
        var next = new RecipeParseCache<>(old);
        assertEquals("default", next.parse("file", "a/b", yaml, () -> "wrong"));
        assertEquals("custom", next.parse("file", List.of("custom", "a", "b"), yaml, () -> "wrong"));
        assertEquals("pack", next.parse("pack", "a/b", yaml, () -> "wrong"));
        assertEquals(3, next.reused());
    }

    @Test void bodyComparisonDoesNotRetainMutableConfigurationSections() throws Exception {
        var yaml = document("result:\n  item: apple\n  lore: [original]\n");
        var old = new RecipeParseCache<String>(null);
        old.parse("file", "soup", yaml, () -> "original");
        yaml.set("result.item", "carrot");
        var next = new RecipeParseCache<>(old);
        assertEquals("modified", next.parse("file", "soup", yaml, () -> "modified"));
        assertEquals(1, next.parsed());
    }

    @Test void fullEpochResetAndFailedDecodersAreRetried() throws Exception {
        var yaml = document("time: 20\n");
        var old = new RecipeParseCache<String>(null);
        assertThrows(IllegalArgumentException.class, () -> old.parse("file", "soup", yaml, () -> {
            throw new IllegalArgumentException("Tag registry not ready");
        }));
        AtomicInteger calls = new AtomicInteger();
        old.parse("file", "soup", yaml, () -> { calls.incrementAndGet(); return null; });
        var next = new RecipeParseCache<>(old);
        assertEquals("ready", next.parse("file", "soup", yaml, () -> "ready"));
        var full = new RecipeParseCache<String>(null);
        assertEquals("new registry", full.parse("file", "soup", yaml, () -> "new registry"));
        assertEquals(1, full.parsed());
    }
}
