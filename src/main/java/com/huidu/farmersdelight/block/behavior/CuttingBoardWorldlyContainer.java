package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.World;

final class CuttingBoardWorldlyContainer extends WorldlyContainerBridge {
    private final CuttingBoardBlockEntity entity;

    CuttingBoardWorldlyContainer(FarmersDelightPlugin plugin, World ceWorld, org.bukkit.World world, BlockPosKey posKey, CuttingBoardBlockEntity entity) {
        super(plugin, ceWorld, world, posKey, 1);
        this.entity = entity;
        this.refreshFromEntity();
    }

    @Override
    protected void readFromEntity() {
        this.items[0] = normalize(BukkitItemManager.instance().wrap(this.entity.getStoredItem()));
    }

    @Override
    protected void writeToEntity() {
        org.bukkit.inventory.ItemStack stack = this.asBukkitStack(this.items[0]);
        if (stack == null || stack.getType().isAir()) {
            this.entity.clearItem();
        } else {
            this.entity.setStoredItem(stack, this.bukkitWorld, this.posKey, CustomBlockUtils.getFacing(this.posKey.toLocation(this.bukkitWorld).getBlock()));
        }
        CuttingBoardBlockBehavior.saveBlockEntityData(this.bukkitWorld, this.posKey);
    }

    @Override
    public int[] getSlotsForFace(Direction direction) {
        return new int[]{0};
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, net.momirealms.craftengine.core.item.Item stack, Direction direction) {
        return slot == 0;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, net.momirealms.craftengine.core.item.Item stack, Direction direction) {
        return slot == 0;
    }
}
