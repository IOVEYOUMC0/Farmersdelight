package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;
import java.util.Optional;

public class TatamiPairingBehavior extends BlockBehavior {

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
    private static volatile String tatamiBlockId = "farmersdelight:tatami";
    private static volatile String facingPropertyName = "facing";

    private final Property<?> facingProperty;
    private final Property<Boolean> pairedProperty;
    private final boolean pairWhileSneaking;

    private TatamiPairingBehavior(BlockDefinition block, Property<?> facingProperty, Property<Boolean> pairedProperty, boolean pairWhileSneaking) {
        super(block);
        this.facingProperty = facingProperty;
        this.pairedProperty = pairedProperty;
        this.pairWhileSneaking = pairWhileSneaking;
    }

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<TatamiPairingBehavior> FACTORY = new BlockBehaviorFactory<TatamiPairingBehavior>() {
        @Override
        public TatamiPairingBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            tatamiBlockId = BehaviorArgParser.getString(arguments, "block-id", tatamiBlockId);
            facingPropertyName = BehaviorArgParser.getString(arguments, "facing-property", facingPropertyName);
            String pairedPropertyName = BehaviorArgParser.getString(arguments, "paired-property", "paired");
            boolean pairWhileSneaking = BehaviorArgParser.getBoolean(arguments, "pair-while-sneaking", false);

            Property<?> facingProperty = block.getProperty(facingPropertyName);
            Property<Boolean> pairedProperty = (Property<Boolean>) block.getProperty(pairedPropertyName);

            return new TatamiPairingBehavior(block, facingProperty, pairedProperty, pairWhileSneaking);
        }
    };

    /**
     * Sets facing from the clicked face on placement, mirroring vanilla TatamiBlock.getStateForPlacement
     * (facing = clickedFace.opposite). Without this the block would keep its default facing and pairing would
     * go in the wrong direction. Floor placement clicks the ground's up-face, giving facing=down (a lone flat
     * mat); placing against another tatami's side gives a horizontal facing, orienting the paired even/odd
     * weave. Sneak-to-suppress-pairing is enforced in placeMultiState.
     */
    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        if (facingProperty == null) {
            return state;
        }
        Direction facing = context.getClickedFace().opposite();
        return withPropertyValue(state, facingProperty, facing.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void placeMultiState(Object thisBlock, Object[] args) {
        if (args.length >= 5) {
            World world = CraftEngineAdapter.toWorld(args[0]);
            // args[1] is the placement position CraftEngine passes as a native Minecraft BlockPos, not the
            // CraftEngine BlockPos type, so convert through the adapter instead of an instanceof cast.
            BlockPos pos = CraftEngineAdapter.toBlockPos(args[1]);
            if (pos == null || world == null) {
                return;
            }

            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (state == null || state.isEmpty() || (!pairWhileSneaking && isPlacerSneaking(args[3]))) {
                return;
            }

            if (pairWithNeighbor(world, pos, state)) {
                // Replace the just-placed block with paired=true so both halves stay visually in sync.
                CraftEngineBlocks.place(block.getLocation(), state.with(pairedProperty, true), false);
            }
        }
    }

    /**
     * Resets paired=false when the facing partner is no longer this same tatami, mirroring vanilla
     * TatamiBlock.updateShape. This is the engine-native neighbor-update hook, dispatched synchronously to the
     * surviving half for every removal cause (break, explosion, piston, fluid, programmatic setBlock), so a
     * broken pair's remaining half returns to the lone-mat appearance and can accept a new pairing. Only a
     * paired tatami can un-pair, so the not-yet-paired half is skipped while its partner is being written during
     * placement and pairing never triggers a false reset. The check keys on the facing partner (not the changed
     * direction) because the neighbor-update hook carries no reliable single changed face; re-checking the
     * partner every call is the direction-agnostic equivalent of vanilla's facing==FACING gate and is idempotent.
     */
    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (args.length < 3 || pairedProperty == null || facingProperty == null) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return;
        }

        Block self = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(self);
        if (!isTatamiState(state) || !Boolean.TRUE.equals(state.get(pairedProperty))) {
            return;
        }

        Block partner = self.getRelative(getFacing(state));
        ImmutableBlockState partnerState = CraftEngineBlocks.getCustomBlockState(partner);
        if (isTatamiState(partnerState) && isSameTatami(state, partnerState)) {
            return;
        }

        CraftEngineBlocks.place(self.getLocation(), state.with(pairedProperty, false), false);
    }

    /**
     * Whether the placing player is sneaking, so pairing can be suppressed like vanilla TatamiBlock.
     * CraftEngine passes the placeMultiState player as a native Minecraft ServerPlayer, not a CraftEngine
     * Player, so bridge it to its Bukkit entity to read the sneak state; a CraftEngine Player is still
     * honored when one is passed.
     */
    private static boolean isPlacerSneaking(Object playerArg) {
        if (playerArg == null) {
            return false;
        }
        if (playerArg instanceof Player cePlayer) {
            return cePlayer.isSecondaryUseActive();
        }
        try {
            Object bukkitEntity = playerArg.getClass().getMethod("getBukkitEntity").invoke(playerArg);
            if (bukkitEntity instanceof org.bukkit.entity.Player bukkitPlayer) {
                return bukkitPlayer.isSneaking();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return false;
    }

    private boolean pairWithNeighbor(World world, BlockPos pos, ImmutableBlockState state) {
        BlockFace facing = getFacing(state);
        BlockPos neighborPos = new BlockPos(
                pos.x() + facing.getModX(),
                pos.y() + facing.getModY(),
                pos.z() + facing.getModZ()
        );
        Block neighborBlock = world.getBlockAt(neighborPos.x(), neighborPos.y(), neighborPos.z());
        ImmutableBlockState neighborState = CraftEngineBlocks.getCustomBlockState(neighborBlock);
        if (neighborState == null || neighborState.isEmpty() || !isSameTatami(state, neighborState)) {
            return false;
        }

        if (pairedProperty == null || facingProperty == null) {
            return false;
        }

        Boolean neighborPaired = neighborState.get(pairedProperty);
        if (Boolean.TRUE.equals(neighborPaired)) {
            return false;
        }

        CraftEngineBlocks.place(
                neighborBlock.getLocation(),
                withFacingAndPair(neighborState, facing.getOppositeFace(), true),
                false
        );
        return true;
    }

    private ImmutableBlockState withFacingAndPair(ImmutableBlockState state, BlockFace facing, boolean paired) {
        ImmutableBlockState result = state;
        if (facingProperty != null) {
            result = withPropertyValue(result, facingProperty, facing.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (pairedProperty != null) {
            result = result.with(pairedProperty, paired);
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ImmutableBlockState withPropertyValue(ImmutableBlockState state, Property property, String valueName) {
        Comparable<?> value = property.valueByName(valueName);
        return value == null ? state : ImmutableBlockState.with(state, property, value);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        return InteractionResult.PASS;
    }

    private BlockFace getFacing(ImmutableBlockState state) {
        return getFacingFromState(state);
    }

    private boolean isSameTatami(ImmutableBlockState first, ImmutableBlockState second) {
        Optional<Key> firstId = first.owner().keyOptional().map(k -> k.location());
        Optional<Key> secondId = second.owner().keyOptional().map(k -> k.location());
        return firstId.isPresent() && firstId.equals(secondId);
    }

    private static boolean isTatamiState(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return false;
        }

        return state.owner().keyOptional()
                .map(Object::toString)
                .filter(tatamiBlockId::equals)
                .isPresent();
    }

    private static BlockFace getFacingFromState(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return BlockFace.NORTH;
        }

        Property<?> property = state.owner().value().getProperty(facingPropertyName);
        if (property == null) {
            return BlockFace.NORTH;
        }

        Object facingValue = state.get(property);
        if (facingValue == null) {
            return BlockFace.NORTH;
        }

        String facingStr = facingValue.toString().toUpperCase(java.util.Locale.ROOT);
        try {
            return BlockFace.valueOf(facingStr);
        } catch (IllegalArgumentException e) {
            return BlockFace.NORTH;
        }
    }

}
