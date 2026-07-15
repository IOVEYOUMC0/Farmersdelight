package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.CraftEngineAdapter;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The young farmersdelight:brown_mushroom / red_mushroom block: a plant that looks exactly like
 * a vanilla mushroom and — because vanilla mushrooms cannot survive on the CraftEngine rich-soil blocks — stands
 * in for one on the rich-soil series. It is placed when a player uses a vanilla mushroom on rich soil (the
 * conditional_block_planting item behavior), survives via bush_block, and drops the vanilla
 * mushroom when broken (its loot table). This behavior owns only the last piece: on random ticks it grows into
 * the matching mushroom colony (age 0), mirroring the mod where a mushroom on rich soil matures into a colony.
 */
public class MushroomBlockBehavior extends BlockBehavior {

    private final Key colonyBlockId;
    private final float growSpeed;
    private final SoilRuleSupport.SoilRules growSoilRules;

    private MushroomBlockBehavior(BlockDefinition block, Key colonyBlockId, float growSpeed,
                                  SoilRuleSupport.SoilRules growSoilRules) {
        super(block);
        this.colonyBlockId = colonyBlockId;
        this.growSpeed = growSpeed;
        this.growSoilRules = growSoilRules;
    }

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    @Override
    public void fallOn(Object thisBlock, Object[] args) {
    }

    @Override
    public void updateEntityMovementAfterFallOn(Object thisBlock, Object[] args) {
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (args.length < 3) {
            return;
        }
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return;
        }

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        // Only mature into a colony while sitting on a valid soil (the rich-soil series), matching the colony's
        // own grow-on-blocks. A young mushroom that somehow sits elsewhere just stays a mushroom.
        if (growSoilRules.isConfigured() && !SoilRuleSupport.matches(block.getRelative(0, -1, 0), growSoilRules)) {
            return;
        }
        if (growSpeed > 0f && ThreadLocalRandom.current().nextFloat() >= growSpeed) {
            return;
        }
        placeColony(block);
    }

    private void placeColony(Block block) {
        BlockDefinition colony = CraftEngineBlocks.byId(colonyBlockId);
        if (colony == null) {
            return;
        }
        // Young colony (age 0) that then grows to maturity via its own random tick; the colony's default state is
        // age 3 (used when the colony item is placed directly), so explicitly drop it to age 0 here.
        CraftEngineBlocks.place(block.getLocation().add(0.5, 0, 0.5), colonyAgeZero(colony), false);
    }

    @SuppressWarnings("unchecked")
    private static ImmutableBlockState colonyAgeZero(BlockDefinition colony) {
        ImmutableBlockState state = colony.defaultState();
        Property<Integer> age = (Property<Integer>) colony.getProperty("age");
        return age != null ? state.with(age, 0) : state;
    }

    public static final BlockBehaviorFactory<MushroomBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public MushroomBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String colonyId = BehaviorArgParser.getStringStrict(arguments, "colony-block", "farmersdelight:brown_mushroom_colony");
            float growSpeed = BehaviorArgParser.getFloat(arguments, "grow-speed", 0.12f);
            SoilRuleSupport.SoilRules growSoilRules = parseGrowSoilRules(arguments);
            return new MushroomBlockBehavior(block, Key.of(colonyId), growSpeed, growSoilRules);
        }
    };

    /** Accepts grow-on-blocks/grow-on-block-tags as aliases of the shared soil-rule keys, mirroring
     * MushroomColonyBehavior so the colony and the young mushroom read the same soil list. */
    private static SoilRuleSupport.SoilRules parseGrowSoilRules(Map<String, Object> arguments) {
        if (BehaviorArgParser.hasArgument(arguments, "grow-on-blocks")
                || BehaviorArgParser.hasArgument(arguments, "grow-on-block-tags")) {
            Map<String, Object> aliased = new HashMap<>(arguments);
            aliased.put("bottom-blocks", aliased.get("grow-on-blocks"));
            aliased.put("bottom-block-tags", aliased.get("grow-on-block-tags"));
            return SoilRuleSupport.parseSoilRules(aliased);
        }
        return SoilRuleSupport.parseSoilRules(arguments);
    }
}
