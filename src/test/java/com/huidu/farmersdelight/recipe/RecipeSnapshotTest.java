package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RecipeSnapshotTest {
    private CookingPotRecipe recipe(String id, String epoch) {
        return new CookingPotRecipe(id, List.of(), null, false, null, 0, 20, epoch, 0);
    }

    private CookingPotRecipeManager.Snapshot snapshot(String epoch, long generation) {
        var base = recipe("base", epoch);
        var custom = recipe("custom", epoch);
        return new CookingPotRecipeManager.Snapshot(Map.of("base", base),
                Map.of("group", Map.of("custom", custom)), Map.of(), Map.of(), List.of(base),
                Map.of("group", List.of(base, custom)), Map.of("group", List.of(custom)), Map.of(), Set.of(),
                0, generation, new HashMap<>(), new HashSet<>(),
                FuzzyRecipeMatcher.compile(List.of(), FoodGroupSnapshot.empty()), Map.of(), List.of(), FoodGroupSnapshot.empty());
    }

    @Test void mergedPublicViewsDoNotMixDefaultAndCustomRecipesAcrossConcurrentPublication() throws Exception {
        var manager = new CookingPotRecipeManager(null);
        var field = CookingPotRecipeManager.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        var first = snapshot("first", 1);
        var second = snapshot("second", 2);
        field.set(manager, first);
        CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newSingleThreadExecutor()) {
            var writer = threads.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 30_000; i++) field.set(manager, (i & 1) == 0 ? first : second);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            start.countDown();
            for (int i = 0; i < 30_000; i++) {
                var merged = manager.getRecipes("group");
                assertEquals(merged.get("base").category(), merged.get("custom").category());
                var all = manager.getAllRecipes();
                assertEquals(all.getFirst().category(), all.getLast().category());
            }
            writer.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void editorGroupsExcludeInheritedRecipesAndRemainImmutableAcrossReload() throws Exception {
        var manager = new CookingPotRecipeManager(null);
        var field = CookingPotRecipeManager.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        field.set(manager, snapshot("old", 1));
        var retained = manager.getCustomRecipeGroups();
        assertEquals(List.of("custom"), manager.getEditableRecipes("group").stream().map(CookingPotRecipe::id).toList());
        assertEquals(List.of("base"), manager.getEditableRecipes(null).stream().map(CookingPotRecipe::id).toList());
        assertTrue(manager.getEditableRecipes("missing").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> retained.clear());
        assertThrows(UnsupportedOperationException.class, () -> retained.get("group").clear());
        assertThrows(UnsupportedOperationException.class, () -> manager.getEditableRecipes("group").clear());
        field.set(manager, snapshot("new", 2));
        assertEquals("old", retained.get("group").get("custom").category());
        assertEquals("new", manager.getEditableRecipes("group").getFirst().category());
    }

    @Test void oldMatchCachesRemainPrivateToTheRetiredSnapshot() throws Exception {
        var manager = new CookingPotRecipeManager(null);
        var field = CookingPotRecipeManager.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        var old = snapshot("old", 1);
        var next = snapshot("new", 2);
        field.set(manager, old);
        Map<String, CookingPotRecipe> retained = manager.getRecipes("group");
        field.set(manager, next);
        old.recipeCache().put("late", recipe("late", "old"));
        old.recipeMisses().add("late-miss");
        assertTrue(next.recipeCache().isEmpty());
        assertTrue(next.recipeMisses().isEmpty());
        assertEquals(2, manager.recipeGeneration());
        assertEquals("old", retained.get("base").category());
        assertEquals("new", manager.getRecipe("group", "base").category());
        assertThrows(UnsupportedOperationException.class, () -> retained.clear());
    }

    @Test void publishedIndexesKeepIndependentCollectionsAndPreserveRecipeOrder() {
        Map<String, Integer> recipes = new LinkedHashMap<>();
        recipes.put("high", 2);
        recipes.put("low", 1);
        var frozen = RecipeCollections.freezeMap(recipes);
        recipes.clear();
        assertEquals(List.of("high", "low"), new ArrayList<>(frozen.keySet()));
        Map<String, Set<String>> index = new HashMap<>();
        index.put("ingredient", new HashSet<>(Set.of("soup")));
        var frozenIndex = RecipeCollections.freezeSets(index);
        index.get("ingredient").clear();
        assertEquals(Set.of("soup"), frozenIndex.get("ingredient"));
        assertThrows(UnsupportedOperationException.class, () -> frozenIndex.get("ingredient").clear());
        List<String> sorted = new ArrayList<>(List.of("high", "low"));
        var frozenSorted = RecipeCollections.freezeLists(Map.of("group", sorted));
        sorted.clear();
        assertEquals(List.of("high", "low"), frozenSorted.get("group"));
        assertThrows(UnsupportedOperationException.class, () -> frozenSorted.get("group").clear());
        Map<String, Integer> children = new HashMap<>(Map.of("soup", 1));
        var nested = RecipeCollections.freezeNested(Map.of("group", children));
        children.clear();
        assertEquals(1, nested.get("group").get("soup"));
        assertThrows(UnsupportedOperationException.class, () -> nested.get("group").clear());
    }
}
