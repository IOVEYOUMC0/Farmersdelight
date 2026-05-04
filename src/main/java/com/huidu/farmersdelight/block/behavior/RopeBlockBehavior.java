package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.block.CustomBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.properties.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.Callable;

public class RopeBlockBehavior extends BlockBehavior {

    private static final String ROPE_BLOCK_ID = "farmersdelight:rope";
    private static final String PROP_NORTH = "north";
    private static final String PROP_SOUTH = "south";
    private static final String PROP_EAST = "east";
    private static final String PROP_WEST = "west";

    private final Property<Boolean> northProperty;
    private final Property<Boolean> southProperty;
    private final Property<Boolean> eastProperty;
    private final Property<Boolean> westProperty;

    private RopeBlockBehavior(CustomBlock block,
                              Property<Boolean> northProperty,
                              Property<Boolean> southProperty,
                              Property<Boolean> eastProperty,
                              Property<Boolean> westProperty) {
        super(block);
        this.northProperty = northProperty;
        this.southProperty = southProperty;
        this.eastProperty = eastProperty;
        this.westProperty = westProperty;
    }

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<RopeBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public RopeBlockBehavior create(CustomBlock block, Map<String, Object> arguments) {
            return new RopeBlockBehavior(
                    block,
                    (Property<Boolean>) block.getProperty(PROP_NORTH),
                    (Property<Boolean>) block.getProperty(PROP_SOUTH),
                    (Property<Boolean>) block.getProperty(PROP_EAST),
                    (Property<Boolean>) block.getProperty(PROP_WEST)
            );
        }
    };

    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();

        ImmutableBlockState result = state;
        if (northProperty != null) result = result.with(northProperty, isRopeAt(world, pos, BlockFace.NORTH));
        if (southProperty != null) result = result.with(southProperty, isRopeAt(world, pos, BlockFace.SOUTH));
        if (eastProperty != null) result = result.with(eastProperty, isRopeAt(world, pos, BlockFace.EAST));
        if (westProperty != null) result = result.with(westProperty, isRopeAt(world, pos, BlockFace.WEST));

        return result;
    }

    @Override
    public void placeMultiState(Object thisBlock, Object[] args, Callable<Object> superMethod) throws Exception {
        superMethod.call();
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;

        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        org.bukkit.inventory.ItemStack hand = bukkitPlayer.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return InteractionResult.PASS;

        String handItemId = ItemUtils.getCustomItemId(hand);
        if (handItemId == null || !handItemId.equals(ROPE_BLOCK_ID)) return InteractionResult.PASS;

        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();

        int targetY = pos.y();
        int checkY = pos.y();
        int worldMinY = world.getMinHeight();
        while (checkY >= worldMinY) {
            checkY--;
            Block below = world.getBlockAt(pos.x(), checkY, pos.z());
            if (!CustomBlockUtils.hasId(below, ROPE_BLOCK_ID)) {
                targetY = checkY + 1;
                break;
            }
        }

        if (targetY == pos.y()) return InteractionResult.PASS;

        Location placeLoc = new Location(world, pos.x() + 0.5, targetY, pos.z() + 0.5);
        Block targetBlock = world.getBlockAt(pos.x(), targetY, pos.z());
        if (!targetBlock.getType().isAir() && !targetBlock.isLiquid()) return InteractionResult.PASS;

        CustomBlock ropeBlock = CraftEngineBlocks.byId(Key.of(ROPE_BLOCK_ID));
        if (ropeBlock == null) return InteractionResult.PASS;

        BlockPos bp = new BlockPos(pos.x(), targetY, pos.z());
        ImmutableBlockState placementState = ropeBlock.defaultState();
        if (northProperty != null) placementState = placementState.with(northProperty, isRopeAt(world, bp, BlockFace.NORTH));
        if (southProperty != null) placementState = placementState.with(southProperty, isRopeAt(world, bp, BlockFace.SOUTH));
        if (eastProperty != null) placementState = placementState.with(eastProperty, isRopeAt(world, bp, BlockFace.EAST));
        if (westProperty != null) placementState = placementState.with(westProperty, isRopeAt(world, bp, BlockFace.WEST));

        if (!CraftEngineBlocks.place(placeLoc, placementState, true)) return InteractionResult.PASS;
        refreshAdjacentRopes(world, bp);

        if (bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
            hand.setAmount(hand.getAmount() - 1);
            if (hand.getAmount() <= 0) {
                bukkitPlayer.getInventory().setItemInMainHand(null);
            }
        }

        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    @Override
    public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;

        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        if (bukkitPlayer == null) return InteractionResult.PASS;
        if (!bukkitPlayer.isSneaking()) return InteractionResult.PASS;

        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();

        int bottomY = pos.y();
        int checkY = pos.y();
        int worldMinY = world.getMinHeight();
        while (checkY >= worldMinY) {
            checkY--;
            Block below = world.getBlockAt(pos.x(), checkY, pos.z());
            if (CustomBlockUtils.hasId(below, ROPE_BLOCK_ID)) {
                bottomY = checkY;
            } else {
                break;
            }
        }

        Block bottomBlock = world.getBlockAt(pos.x(), bottomY, pos.z());
        boolean isCreative = bukkitPlayer.getGameMode() == GameMode.CREATIVE;

        if (bottomY != pos.y() && !isCreative) {
            org.bukkit.inventory.ItemStack recovered = buildRopeItem();
            if (recovered == null || !bukkitPlayer.getInventory().addItem(recovered).isEmpty()) {
                return InteractionResult.PASS;
            }
        }

        CraftEngineBlocks.remove(bottomBlock);
        BlockPos bp = new BlockPos(pos.x(), bottomY, pos.z());
        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugin("FarmersDelight"),
                () -> refreshAdjacentRopes(world, bp)
        );
        return InteractionResult.SUCCESS;
    }

    private static org.bukkit.inventory.ItemStack buildRopeItem() {
        try {
            var item = BukkitCraftEngine.instance().itemManager()
                    .getCustomItem(Key.of(ROPE_BLOCK_ID))
                    .orElse(null);
            if (item != null) return item.buildItemStack();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isRopeAt(World world, BlockPos pos, BlockFace direction) {
        Block neighbor = world.getBlockAt(
                pos.x() + direction.getModX(),
                pos.y() + direction.getModY(),
                pos.z() + direction.getModZ()
        );
        return CustomBlockUtils.hasId(neighbor, ROPE_BLOCK_ID);
    }

    public static void refreshAdjacentRopes(World world, BlockPos pos) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block neighbor = world.getBlockAt(
                    pos.x() + face.getModX(),
                    pos.y() + face.getModY(),
                    pos.z() + face.getModZ()
            );
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(neighbor);
            if (state == null || state.isEmpty() || !CustomBlockUtils.hasId(state, ROPE_BLOCK_ID)) {
                continue;
            }

            BlockPos neighborPos = new BlockPos(
                    pos.x() + face.getModX(),
                    pos.y() + face.getModY(),
                    pos.z() + face.getModZ()
            );

            Property<?> nProp = state.owner().value().getProperty(PROP_NORTH);
            Property<?> sProp = state.owner().value().getProperty(PROP_SOUTH);
            Property<?> eProp = state.owner().value().getProperty(PROP_EAST);
            Property<?> wProp = state.owner().value().getProperty(PROP_WEST);

            boolean northConn = isRopeAt(world, neighborPos, BlockFace.NORTH);
            boolean southConn = isRopeAt(world, neighborPos, BlockFace.SOUTH);
            boolean eastConn = isRopeAt(world, neighborPos, BlockFace.EAST);
            boolean westConn = isRopeAt(world, neighborPos, BlockFace.WEST);

            ImmutableBlockState updated = state;
            if (nProp instanceof Property<?>) {
                @SuppressWarnings("unchecked")
                Property<Boolean> northP = (Property<Boolean>) nProp;
                updated = updated.with(northP, northConn);
            }
            if (sProp instanceof Property<?>) {
                @SuppressWarnings("unchecked")
                Property<Boolean> southP = (Property<Boolean>) sProp;
                updated = updated.with(southP, southConn);
            }
            if (eProp instanceof Property<?>) {
                @SuppressWarnings("unchecked")
                Property<Boolean> eastP = (Property<Boolean>) eProp;
                updated = updated.with(eastP, eastConn);
            }
            if (wProp instanceof Property<?>) {
                @SuppressWarnings("unchecked")
                Property<Boolean> westP = (Property<Boolean>) wProp;
                updated = updated.with(westP, westConn);
            }

            CraftEngineBlocks.place(neighbor.getLocation(), updated, false);
        }
    }
}
