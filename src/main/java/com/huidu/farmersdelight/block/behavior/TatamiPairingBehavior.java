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
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;
import java.util.Optional;

public class TatamiPairingBehavior extends BlockBehavior {
    private static final BlockFace[] ORTHOGONAL_FACES = {
            BlockFace.NORTH,
            BlockFace.EAST,
            BlockFace.SOUTH,
            BlockFace.WEST,
            BlockFace.UP,
            BlockFace.DOWN
    };

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
    private static volatile String pairedPropertyName = "paired";

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
            pairedPropertyName = BehaviorArgParser.getString(arguments, "paired-property", pairedPropertyName);
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

    public static void refreshAdjacentTatami(Location brokenLocation) {
        if (brokenLocation == null || brokenLocation.getWorld() == null) {
            return;
        }

        World world = brokenLocation.getWorld();
        Block centerBlock = world.getBlockAt(brokenLocation);
        for (BlockFace face : ORTHOGONAL_FACES) {
            refreshTatamiState(centerBlock.getRelative(face), brokenLocation);
        }
    }

    private BlockFace getFacing(ImmutableBlockState state) {
        return getFacingFromState(state);
    }

    private boolean isSameTatami(ImmutableBlockState first, ImmutableBlockState second) {
        Optional<Key> firstId = first.owner().keyOptional().map(k -> k.location());
        Optional<Key> secondId = second.owner().keyOptional().map(k -> k.location());
        return firstId.isPresent() && firstId.equals(secondId);
    }

    private static void refreshTatamiState(Block block, Location brokenLocation) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (!isTatamiState(state)) {
            return;
        }

        Boolean paired = getBooleanProperty(state, pairedPropertyName);
        if (!Boolean.TRUE.equals(paired)) {
            return;
        }

        BlockFace facing = getFacingFromState(state);
        Block partnerBlock = block.getRelative(facing);
        // Reset when the facing partner is gone. Match the broken block's position directly instead of only
        // testing whether the partner still reads as a tatami: CraftEngine may not have cleared the removed
        // block's custom state by the time this runs, so a plain state check could still see the old tatami
        // and skip the reset, leaving an orphaned half stuck as paired (and refusing to pair again).
        boolean partnerGone = isSameBlock(partnerBlock, brokenLocation)
                || !isTatamiState(CraftEngineBlocks.getCustomBlockState(partnerBlock));
        if (!partnerGone) {
            return;
        }

        Property<Boolean> pairedProperty = getBooleanPropertyDefinition(state, pairedPropertyName);
        if (pairedProperty == null) {
            return;
        }

        ImmutableBlockState unpairedState = state.with(pairedProperty, false);
        CraftEngineBlocks.place(block.getLocation(), unpairedState, false);
    }

    private static boolean isSameBlock(Block block, Location location) {
        return location != null
                && location.getWorld() != null
                && block.getWorld().equals(location.getWorld())
                && block.getX() == location.getBlockX()
                && block.getY() == location.getBlockY()
                && block.getZ() == location.getBlockZ();
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

    @SuppressWarnings("unchecked")
    private static Property<Boolean> getBooleanPropertyDefinition(ImmutableBlockState state, String propertyName) {
        if (state == null || state.isEmpty()) {
            return null;
        }

        Property<?> property = state.owner().value().getProperty(propertyName);
        if (property instanceof Property<?> typedProperty) {
            return (Property<Boolean>) typedProperty;
        }
        return null;
    }

    private static Boolean getBooleanProperty(ImmutableBlockState state, String propertyName) {
        Property<Boolean> property = getBooleanPropertyDefinition(state, propertyName);
        if (property == null) {
            return null;
        }
        return state.get(property);
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

