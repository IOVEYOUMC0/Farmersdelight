package com.huidu.farmersdelight.registry;

import com.huidu.farmersdelight.block.behavior.*;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.item.behavior.ConditionalBlockPlantingItemBehavior;
import com.huidu.farmersdelight.util.Constants;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;

import java.util.logging.Logger;

/**
 * Registers FarmersDelight's CraftEngine block and item behavior factories. Extracted from the plugin
 * main class so the (verbose, append-only) registration list lives in one focused place. Registration
 * is idempotent: a factory is only registered if its key isn't already present, so re-invocation is safe.
 */
public final class BehaviorRegistrar {

    private BehaviorRegistrar() {
    }

    public static void registerBlockBehaviors(Logger logger) {
        registerBehavior(Constants.BEHAVIOR_COOKING_POT, CookingPotBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_CUTTING_BOARD, CuttingBoardBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_SKILLET, SkilletBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_STOVE, StoveCookingBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TALL_CROP, TallCropBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TATAMI, TatamiPairingBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_UPPER_HALF_LOOT_RELAY, UpperHalfLootRelayBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_WILD_RICE, WildRiceBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_ROPE, RopeBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_MUSHROOM_COLONY, MushroomColonyBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_WILD_PLANT, WildPlantBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TOMATO_VINE, TomatoVineBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_ORGANIC_COMPOST, OrganicCompostBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_RICH_SOIL, RichSoilBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_RICH_SOIL_FARMLAND, RichSoilFarmlandBlockBehavior.FACTORY);

        logger.info(I18n.formatConsole("plugin.registered_block_behaviors"));
    }

    public static void registerItemBehaviors() {
        registerItemBehavior(Constants.ITEM_BEHAVIOR_CONDITIONAL_PLANTING, ConditionalBlockPlantingItemBehavior.FACTORY);
    }

    private static void registerBehavior(String key, BlockBehaviorFactory<?> factory) {
        Key keyObj = Key.of(key);
        if (BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(keyObj) == null) {
            BlockBehaviors.register(keyObj, factory);
        }
    }

    private static void registerItemBehavior(String key, ItemBehaviorFactory<?> factory) {
        Key keyObj = Key.of(key);
        if (BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(keyObj) == null) {
            ItemBehaviors.register(keyObj, factory);
        }
    }
}
