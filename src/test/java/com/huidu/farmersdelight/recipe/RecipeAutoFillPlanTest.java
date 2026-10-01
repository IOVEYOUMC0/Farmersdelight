package com.huidu.farmersdelight.recipe;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RecipeAutoFillPlanTest {
    private static final String BEEF = "minecraft:beef", TOMATO = "farmersdelight:tomato";
    private static RecipeAutoFillPlan.Plan plan(List<String> ids, ItemStack[] source, ItemStack[] target, boolean creative) {
        return RecipeAutoFillPlan.prepare(ids, source, target, creative, item -> ((Stack) item).id);
    }
    @Test void duplicateIngredientsUseRealUnitsAndSeparateInputSlots() {
        ItemStack[] source = {new Stack(BEEF, 3, "enchanted/custom-name"), new Stack(TOMATO, 1, "plugin-data")};
        var plan = plan(List.of(BEEF, BEEF, TOMATO), source, new ItemStack[6], false);
        assertEquals(RecipeAutoFillPlan.Result.FILLED, plan.result());
        assertEquals(1, plan.playerItems()[0].getAmount());
        assertNull(plan.playerItems()[1]);
        assertEquals("enchanted/custom-name", ((Stack) plan.potItems()[0]).payload);
        assertEquals("plugin-data", ((Stack) plan.potItems()[2]).payload);
        assertNotSame(source[0], plan.potItems()[0]);
        assertEquals(3, source[0].getAmount());
    }
    @Test void missingSecondCopyDoesNotConsumeTheFirstOrModifyThePot() {
        ItemStack[] source = {new Stack(BEEF, 1, "data")};
        ItemStack[] pot = new ItemStack[6];
        assertEquals(RecipeAutoFillPlan.Result.MISSING_ITEMS, plan(List.of(BEEF, BEEF), source, pot, false).result());
        assertEquals(1, source[0].getAmount());
        assertNull(pot[0]);
    }
    @Test void existingRequiredFoodIsNotDebitedAgainAndDoubleClickIsIdempotent() {
        ItemStack[] source = {new Stack(BEEF, 64, "data"), new Stack(TOMATO, 1, "data")};
        ItemStack[] pot = {new Stack(BEEF, 4, "existing"), null};
        var first = plan(List.of(BEEF, TOMATO), source, pot, false);
        assertEquals(64, first.playerItems()[0].getAmount());
        assertEquals(4, first.potItems()[0].getAmount());
        assertEquals(RecipeAutoFillPlan.Result.ALREADY_FILLED,
                plan(List.of(BEEF, TOMATO), first.playerItems(), first.potItems(), false).result());
    }
    @Test void unrelatedFoodAndInsufficientSlotsAbortWithoutPartialTransfer() {
        ItemStack[] source = {new Stack(BEEF, 2, "data")};
        assertEquals(RecipeAutoFillPlan.Result.NO_SPACE, plan(List.of(BEEF, BEEF), source, new ItemStack[1], false).result());
        assertEquals(RecipeAutoFillPlan.Result.OTHER_INGREDIENTS,
                plan(List.of(BEEF), source, new ItemStack[]{new Stack(TOMATO, 1, "data"), null}, false).result());
        assertEquals(2, source[0].getAmount());
    }
    @Test void creativeSkipsCommittedDebitButStillRequiresTheRealIngredients() {
        ItemStack[] source = {new Stack(BEEF, 2, "data")};
        assertEquals(2, plan(List.of(BEEF, BEEF), source, new ItemStack[6], true).playerItems()[0].getAmount());
        assertEquals(RecipeAutoFillPlan.Result.MISSING_ITEMS, plan(List.of(BEEF, BEEF, BEEF), source, new ItemStack[6], true).result());
    }
    @Test void identitiesAreResolvedOncePerNonemptySlotAndSubstitutionsPreserveActualItems() {
        AtomicInteger calls = new AtomicInteger();
        var plan = RecipeAutoFillPlan.prepare(List.of(BEEF, BEEF), new ItemStack[]{new Stack("minecraft:porkchop", 2, "real-pork")},
                new ItemStack[6], false, item -> { calls.incrementAndGet(); return ((Stack) item).id; }, (expected, actual) -> true);
        assertEquals(1, calls.get());
        assertEquals("minecraft:porkchop", ((Stack) plan.potItems()[0]).id);
        assertEquals("real-pork", ((Stack) plan.potItems()[0]).payload);
    }
    @Test void malformedOrEmptyRecipeCannotProduceAPlan() {
        assertEquals(RecipeAutoFillPlan.Result.INVALID_RECIPE, plan(List.of(), new ItemStack[0], new ItemStack[6], false).result());
        assertEquals(RecipeAutoFillPlan.Result.INVALID_RECIPE, plan(List.of("invalid"), new ItemStack[0], new ItemStack[6], false).result());
    }
    static final class Stack extends ItemStack {
        final String id, payload;
        private int amount;
        Stack(String id, int amount, String payload) { super(); this.id = id; this.amount = amount; this.payload = payload; }
        @Override public Material getType() { return Material.STONE; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public ItemStack clone() { return new Stack(id, amount, payload); }
    }
}
