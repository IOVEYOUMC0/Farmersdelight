package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.WorldlyContainer;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class WorldlyContainerBridge {
    private static final int[] COOKING_POT_ALL_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    private static final int[] COOKING_POT_INGREDIENT_SLOTS = {0, 1, 2, 3, 4, 5};
    private static final int[] COOKING_POT_OUTPUT_SLOT = {CookingPotBlockBehavior.SLOT_OUTPUT};
    private static final int[] CUTTING_BOARD_SLOT = {0};

    private WorldlyContainerBridge() {
    }

    public static Object cookingPotContainer(Object[] args) {
        Context context = context(args);
        if (context == null) {
            return null;
        }

        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(context.world, context.posKey);
        if (entity == null) {
            CookingPotBlockBehavior.loadBlockEntity(context.world, context.posKey);
            entity = CookingPotBlockBehavior.getBlockEntity(context.world, context.posKey);
        }
        if (entity == null) {
            entity = CookingPotBlockBehavior.getOrCreateBlockEntity(context.location);
        }
        return entity == null ? null : new CookingPotContainer(context, entity);
    }

    public static Object cuttingBoardContainer(Object[] args) {
        Context context = context(args);
        if (context == null) {
            return null;
        }

        CuttingBoardBlockEntity entity = CuttingBoardBlockBehavior.getBlockEntity(context.world, context.posKey);
        if (entity == null) {
            CuttingBoardBlockBehavior.loadBlockEntity(context.world, context.posKey);
            entity = CuttingBoardBlockBehavior.getBlockEntity(context.world, context.posKey);
        }
        if (entity == null) {
            entity = CuttingBoardBlockBehavior.putBlockEntity(
                    context.world,
                    context.posKey,
                    new CuttingBoardBlockEntity(context.posKey, context.world)
            );
        }
        return entity == null ? null : new CuttingBoardContainer(context, entity);
    }

    @Nullable
    private static Context context(Object[] args) {
        if (args == null || args.length < 3) {
            return null;
        }

        World world = LevelProxy.INSTANCE.getWorld(args[1]);
        net.momirealms.craftengine.core.world.BlockPos blockPos = LocationUtils.fromBlockPos(args[2]);
        BlockPosKey posKey = new BlockPosKey(blockPos);
        Location location = posKey.toLocation(world);
        return new Context(world, posKey, location);
    }

    private static Item wrap(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return Item.empty();
        }
        return BukkitAdaptor.adapt(stack.clone());
    }

    @Nullable
    private static ItemStack unwrap(Item item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        if (item instanceof BukkitItem bukkitItem) {
            ItemStack stack = bukkitItem.getBukkitItem();
            return stack == null ? null : stack.clone();
        }
        ItemStack stack = ItemStackUtils.getBukkitStack(item);
        return stack == null ? null : stack.clone();
    }

    private record Context(World world, BlockPosKey posKey, Location location) {
        WorldPosition position() {
            return LocationUtils.toWorldPosition(location);
        }
    }

    private abstract static class BaseContainer implements WorldlyContainer {
        protected final Context context;

        BaseContainer(Context context) {
            this.context = context;
        }

        @Override
        public int maxStackSize() {
            return 64;
        }

        @Override
        public void setChanged() {
            save();
        }

        @Override
        public boolean stillValid(Player player) {
            return player == null || player.canInteractPoint(context.position().toVec3d(), player.getCachedInteractionRange());
        }

        @Override
        public List<Item> contents() {
            return java.util.stream.IntStream.range(0, containerSize())
                    .mapToObj(this::getItem)
                    .toList();
        }

        @Override
        public void setMaxStackSize(int size) {
        }

        @Override
        public WorldPosition position() {
            return context.position();
        }

        protected abstract void save();
    }

    private static final class CookingPotContainer extends BaseContainer {
        private final CookingPotBlockEntity entity;

        private CookingPotContainer(Context context, CookingPotBlockEntity entity) {
            super(context);
            this.entity = entity;
        }

        @Override
        public int containerSize() {
            return CookingPotBlockBehavior.INVENTORY_SIZE;
        }

        @Override
        public boolean isEmpty() {
            for (int slot = 0; slot < containerSize(); slot++) {
                if (!getItem(slot).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Item getItem(int slot) {
            return wrap(entity.getInventorySlot(slot));
        }

        @Override
        public Item removeItem(int slot, int count) {
            if (slot == CookingPotBlockBehavior.SLOT_OUTPUT) {
                ItemStack result = entity.takeMealPortionForDelivery(context.world, count);
                if (result == null || result.getType().isAir()) {
                    return Item.empty();
                }
                save();
                return wrap(result);
            }

            ItemStack stack = entity.getInventorySlot(slot);
            if (stack == null || stack.getType().isAir()) {
                return Item.empty();
            }

            int amount = Math.max(1, Math.min(count, stack.getAmount()));
            ItemStack result = stack.clone();
            result.setAmount(amount);
            stack.setAmount(stack.getAmount() - amount);
            entity.setInventorySlot(slot, stack.getAmount() <= 0 ? null : stack);
            afterSlotChanged(slot);
            return wrap(result);
        }

        @Override
        public Item removeItemNoUpdate(int slot) {
            return removeItem(slot, 1);
        }

        @Override
        public void setItem(int slot, Item item) {
            entity.setInventorySlot(slot, unwrap(item));
            afterSlotChanged(slot);
        }

        @Override
        public boolean canPlaceItem(int slot, Item item) {
            return slot >= 0 && slot < CookingPotBlockBehavior.SLOT_MEAL_DISPLAY
                    || slot == CookingPotBlockBehavior.SLOT_CONTAINER;
        }

        @Override
        public void clearContent() {
            for (int slot = 0; slot < containerSize(); slot++) {
                entity.setInventorySlot(slot, null);
            }
            save();
        }

        @Override
        public int[] getSlotsForFace(Direction direction) {
            if (direction == Direction.UP) {
                return COOKING_POT_INGREDIENT_SLOTS;
            }
            if (direction == Direction.DOWN) {
                return COOKING_POT_OUTPUT_SLOT;
            }
            return COOKING_POT_ALL_SLOTS;
        }

        @Override
        public boolean canPlaceItemThroughFace(int slot, Item stack, Direction direction) {
            if (direction == Direction.UP) {
                return slot >= 0 && slot < CookingPotBlockBehavior.SLOT_MEAL_DISPLAY;
            }
            return direction != Direction.DOWN && slot == CookingPotBlockBehavior.SLOT_CONTAINER;
        }

        @Override
        public boolean canTakeItemThroughFace(int slot, Item stack, Direction direction) {
            if (direction == Direction.UP) {
                return slot >= 0 && slot < CookingPotBlockBehavior.SLOT_MEAL_DISPLAY;
            }
            return slot == CookingPotBlockBehavior.SLOT_OUTPUT;
        }

        @Override
        protected void save() {
            CookingPotBlockBehavior.saveBlockEntityData(context.world, context.posKey);
        }

        private void afterSlotChanged(int slot) {
            if (slot == CookingPotBlockBehavior.SLOT_OUTPUT || slot == CookingPotBlockBehavior.SLOT_CONTAINER) {
                entity.tryMovePendingToOutput();
            }
            save();
        }
    }

    private static final class CuttingBoardContainer extends BaseContainer {
        private final CuttingBoardBlockEntity entity;

        private CuttingBoardContainer(Context context, CuttingBoardBlockEntity entity) {
            super(context);
            this.entity = entity;
        }

        @Override
        public int containerSize() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return !entity.hasItem();
        }

        @Override
        public Item getItem(int slot) {
            return slot == 0 ? wrap(entity.getStoredItem()) : Item.empty();
        }

        @Override
        public Item removeItem(int slot, int count) {
            if (slot != 0 || !entity.hasItem()) {
                return Item.empty();
            }

            ItemStack stored = entity.getStoredItem();
            int amount = Math.max(1, Math.min(count, stored.getAmount()));
            ItemStack result = stored.clone();
            result.setAmount(amount);
            stored.setAmount(stored.getAmount() - amount);
            if (stored.getAmount() <= 0) {
                entity.clearItem();
            } else {
                entity.setStoredItem(stored, context.world, context.posKey, facing());
            }
            save();
            return wrap(result);
        }

        @Override
        public Item removeItemNoUpdate(int slot) {
            return removeItem(slot, 1);
        }

        @Override
        public void setItem(int slot, Item item) {
            if (slot != 0) {
                return;
            }

            ItemStack stack = unwrap(item);
            if (stack == null || stack.getType().isAir()) {
                entity.clearItem();
            } else {
                entity.setItem(stack, context.world, context.posKey, facing());
            }
            save();
        }

        @Override
        public boolean canPlaceItem(int slot, Item item) {
            return slot == 0 && !entity.isItemCarved();
        }

        @Override
        public void clearContent() {
            entity.clearItem();
            save();
        }

        @Override
        public int[] getSlotsForFace(Direction direction) {
            return CUTTING_BOARD_SLOT;
        }

        @Override
        public boolean canPlaceItemThroughFace(int slot, Item stack, Direction direction) {
            return canPlaceItem(slot, stack);
        }

        @Override
        public boolean canTakeItemThroughFace(int slot, Item stack, Direction direction) {
            return slot == 0;
        }

        @Override
        protected void save() {
            CuttingBoardBlockBehavior.saveBlockEntityData(context.world, context.posKey);
        }

        private BlockFace facing() {
            return CustomBlockUtils.getFacing(context.location.getBlock());
        }
    }
}
