package com.huidu.farmersdelight.recipe;

import org.bukkit.inventory.ItemStack;

import java.util.List;

public record CookingPotRecipe(String id, List<RecipeIngredient> ingredients, ItemStack container,
                               boolean needsContainer, ItemStack result, float experience, int cookTime,
                               String category, int priority, FuzzyRecipeSpec fuzzy,
                               java.util.Map<String, Integer> matchedInputs) {
    /** Retains the constructor used by existing addons. */
    public CookingPotRecipe(String id, List<RecipeIngredient> ingredients, ItemStack container,
                            boolean needsContainer, ItemStack result, float experience, int cookTime,
                            String category, int priority) {
        this(id, ingredients, container, needsContainer, result, experience, cookTime, category, priority, null, null);
    }

    public CookingPotRecipe(String id, List<RecipeIngredient> ingredients, ItemStack container,
                            boolean needsContainer, ItemStack result, float experience, int cookTime,
                            String category, int priority, FuzzyRecipeSpec fuzzy) {
        this(id, ingredients, container, needsContainer, result, experience, cookTime, category, priority, fuzzy, null);
    }

    public CookingPotRecipe {
        if (matchedInputs != null) matchedInputs = java.util.Map.copyOf(matchedInputs);
    }

    public boolean isFuzzy() { return fuzzy != null; }
    public String getId() {
        return id;
    }

    public List<RecipeIngredient> getIngredients() {
        return ingredients;
    }

    public ItemStack getContainer() {
        return container;
    }

    public boolean getNeedsContainer() {
        return needsContainer;
    }

    public ItemStack getResult() {
        return result;
    }

    public float getExperience() {
        return experience;
    }

    public int getCookTime() {
        return cookTime;
    }

    public String getCategory() {
        return category;
    }

    public int getPriority() {
        return priority;
    }
}

