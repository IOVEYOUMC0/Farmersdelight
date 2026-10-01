package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class FuzzyRecipeMatcherTest {
    private static final String BEEF = "minecraft:beef", PORK = "minecraft:porkchop", TOMATO = "farmersdelight:tomato", SUGAR = "minecraft:sugar";
    private static FuzzyRecipeMatcher.Definition recipe(String id, Map<String, Integer> ideal, boolean equivalent, boolean seasoning, int priority) {
        return new FuzzyRecipeMatcher.Definition(id, new FuzzyRecipeSpec(ideal, equivalent, seasoning, 0.15), priority);
    }
    private static FoodGroupSnapshot groups() {
        return FoodGroupSnapshot.of(List.of(
                new FoodGroupSnapshot.Group("farmersdelight:red_meat", FoodGroupSnapshot.Kind.EQUIVALENT, List.of(BEEF, PORK)),
                new FoodGroupSnapshot.Group("farmersdelight:seasonings", FoodGroupSnapshot.Kind.SEASONING, List.of(SUGAR))));
    }
    @Test void missingRequiredFoodCannotSelectAnOtherwiseSimilarDish() {
        var matcher = FuzzyRecipeMatcher.compile(List.of(recipe("stew", Map.of(BEEF, 3, TOMATO, 1), true, true, 0)), groups());
        assertNull(matcher.match(Map.of(BEEF, 6)));
    }
    @Test void moreSpecificDishWinsWhenAllOfItsIngredientsArePresent() {
        var matcher = FuzzyRecipeMatcher.compile(List.of(recipe("meat", Map.of(BEEF, 1), true, true, 0),
                recipe("stew", Map.of(BEEF, 3, TOMATO, 1), true, true, 0)), groups());
        assertEquals("stew", matcher.match(Map.of(BEEF, 1, TOMATO, 4)).id());
    }
    @Test void closestRatioWinsWithinTheSameSpecificity() {
        var matcher = FuzzyRecipeMatcher.compile(List.of(recipe("meaty", Map.of(BEEF, 3, TOMATO, 1), false, true, 0),
                recipe("tomato", Map.of(BEEF, 1, TOMATO, 3), false, true, 0)), groups());
        assertEquals("tomato", matcher.match(Map.of(BEEF, 1, TOMATO, 3)).id());
    }
    @Test void tiedRecipesKeepTheirConfigurationOrderAndPriorityIsExplicit() {
        var first = recipe("z_first", Map.of(BEEF, 1), true, true, 0);
        var second = recipe("a_second", Map.of(BEEF, 1), true, true, 0);
        assertEquals("z_first", FuzzyRecipeMatcher.compile(List.of(first, second), groups()).match(Map.of(BEEF, 1)).id());
        assertEquals("priority", FuzzyRecipeMatcher.compile(List.of(first,
                recipe("priority", Map.of(BEEF, 2), true, true, 1)), groups()).match(Map.of(BEEF, 1)).id());
    }
    @Test void equivalentMembersCombineTheirActualAndIdealWeights() {
        var matcher = FuzzyRecipeMatcher.compile(List.of(recipe("meat", Map.of(BEEF, 1, PORK, 1), true, true, 0)), groups());
        var match = matcher.match(Map.of(PORK, 2));
        assertNotNull(match);
        assertEquals(1, match.portions());
        assertEquals(FuzzyRecipeMatcher.Quality.SUPERB, match.quality());
        assertEquals(1, match.similarity(), 1e-12);
    }
    @Test void perRecipeEquivalentToggleRestoresConcreteIdentity() {
        var matcher = FuzzyRecipeMatcher.compile(List.of(recipe("meat", Map.of(BEEF, 1), false, true, 0)), groups());
        assertNull(matcher.match(Map.of(PORK, 1)));
    }
    @Test void seasoningIsConsumedButDoesNotAffectSelectionPortionsOrQuality() {
        var enabled = FuzzyRecipeMatcher.compile(List.of(recipe("meat", Map.of(BEEF, 1), true, true, 0)), groups());
        var disabled = FuzzyRecipeMatcher.compile(List.of(recipe("meat", Map.of(BEEF, 1), true, false, 0)), groups());
        assertEquals(FuzzyRecipeMatcher.Quality.SUPERB, enabled.match(Map.of(BEEF, 2, SUGAR, 2)).quality());
        assertEquals(2, enabled.match(Map.of(BEEF, 2, SUGAR, 2)).portions());
        assertEquals(FuzzyRecipeMatcher.Quality.STANDARD, disabled.match(Map.of(BEEF, 2, SUGAR, 2)).quality());
        assertNull(enabled.match(Map.of(SUGAR, 6)));
    }
    @Test void portionsRequireCompleteIdealSetsAndDeviationsLowerQuality() {
        var matcher = FuzzyRecipeMatcher.compile(List.of(recipe("stew", Map.of(BEEF, 2, TOMATO, 1), false, true, 0)), groups());
        assertEquals(2, matcher.match(Map.of(BEEF, 4, TOMATO, 2)).portions());
        assertEquals(FuzzyRecipeMatcher.Quality.SUPERB, matcher.match(Map.of(BEEF, 4, TOMATO, 2)).quality());
        assertEquals(1, matcher.match(Map.of(BEEF, 1, TOMATO, 1)).portions());
        assertEquals(FuzzyRecipeMatcher.Quality.EXCELLENT, matcher.match(Map.of(BEEF, 1, TOMATO, 1)).quality());
        assertEquals(FuzzyRecipeMatcher.Quality.POOR, matcher.match(Map.of(BEEF, 6, TOMATO, 1)).quality());
    }
    @Test void thresholdRejectsUnrelatedInputAndFirstEquivalentGroupWins() {
        var two = FoodGroupSnapshot.of(List.of(
                new FoodGroupSnapshot.Group("fd:first", FoodGroupSnapshot.Kind.EQUIVALENT, List.of(BEEF, PORK)),
                new FoodGroupSnapshot.Group("fd:second", FoodGroupSnapshot.Kind.EQUIVALENT, List.of(PORK, TOMATO))));
        assertEquals("#fd:first", two.canonical(PORK));
        var matcher = FuzzyRecipeMatcher.compile(List.of(recipe("meat", Map.of(BEEF, 1), false, false, 0)), two);
        assertNull(matcher.match(Map.of(BEEF, 1, TOMATO, 54)));
    }
    @Test void groupSnapshotsDoNotChangeAlreadyCompiledMatchers() {
        var definition = recipe("meat", Map.of(BEEF, 1), true, true, 0);
        var old = FuzzyRecipeMatcher.compile(List.of(definition), groups());
        var changed = FuzzyRecipeMatcher.compile(List.of(definition), FoodGroupSnapshot.empty());
        assertNotNull(old.match(Map.of(PORK, 1)));
        assertNull(changed.match(Map.of(PORK, 1)));
    }
    @Test void mappingAndLegacyListSyntaxPreserveRatiosAndBothToggles() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("match-mode: fuzzy\nperfect:\n  minecraft:beef: 2\n  farmersdelight:tomato: 1\nuse_equivalent_foods: false\nuse-seasonings: false\nminimum-score: 0.2\n");
        var first = CookingPotRecipeManager.parseFuzzy(yaml);
        assertEquals(Map.of(BEEF, 2, TOMATO, 1), first.perfect());
        assertFalse(first.useEquivalentFoods());
        assertFalse(first.useSeasonings());
        assertEquals(0.2, first.minimumScore());
        yaml.loadFromString("perfect: ['minecraft:beef 2', 'farmersdelight:tomato 1']\n");
        assertEquals(first.perfect(), CookingPotRecipeManager.parseFuzzy(yaml).perfect());
    }
    @Test void invalidWeightsModesAndNonFiniteScoresFailBeforePublication() throws Exception {
        for (String text : List.of("match-mode: fuzzy\nperfect: {minecraft:beef: 0}", "match-mode: fuzzy\nperfect: {minecraft:beef: 1.5}",
                "match-mode: fuzzy\nperfect: {minecraft:beef: 65}", "match-mode: fuzzy", "match-mode: typo")) {
            var yaml = new YamlConfiguration(); yaml.loadFromString(text);
            assertThrows(IllegalArgumentException.class, () -> CookingPotRecipeManager.parseFuzzy(yaml), text);
        }
        assertThrows(IllegalArgumentException.class, () -> new FuzzyRecipeSpec(Map.of(BEEF, 1), true, true, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new FuzzyRecipeSpec(Map.of(BEEF, 1), true, true, Double.POSITIVE_INFINITY));
    }
    @Test void legacyRecipeConstructorAndExactModeRemainSupported() {
        var recipe = new CookingPotRecipe("old", List.of(), null, false, null, 0, 20, "misc", 0);
        assertFalse(recipe.isFuzzy());
        assertNull(CookingPotRecipeManager.parseFuzzy(new YamlConfiguration()));
    }
    @Test void ingredientIndexKeepsSelectionCorrectWithThousandsOfOtherDishes() {
        var recipes = new java.util.ArrayList<FuzzyRecipeMatcher.Definition>();
        for (int index = 0; index < 10_000; index++) recipes.add(recipe("dish_" + index,
                Map.of(BEEF, 1, "example:vegetable_" + index, 1), false, false, 0));
        var matcher = FuzzyRecipeMatcher.compile(recipes, FoodGroupSnapshot.empty());
        assertEquals("dish_9876", matcher.match(Map.of(BEEF, 1, "example:vegetable_9876", 1)).id());
        assertNull(matcher.match(Map.of("minecraft:stone", 6)));
    }
    @Test void tiedDishesInDifferentInputViewsStillKeepConfigurationOrder() {
        var first = recipe("first", Map.of(BEEF, 1, TOMATO, 1), true, true, 0);
        var second = recipe("second", Map.of(BEEF, 1, "minecraft:carrot", 1), false, false, 0);
        var matcher = FuzzyRecipeMatcher.compile(List.of(first, second), groups());
        assertEquals("first", matcher.match(Map.of(BEEF, 1, TOMATO, 1, "minecraft:carrot", 1)).id());
    }
}
