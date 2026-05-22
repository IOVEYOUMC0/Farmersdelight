package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.World;

final class CookingPotWorldlyContainer extends WorldlyContainerBridge {
    private final CookingPotBlockEntity entity;

    CookingPotWorldlyContainer(FarmersDelightPlugin plugin, World ceWorld, org.bukkit.World world, BlockPosKey posKey, CookingPotBlockEntity entity) {
        super(plugin, ceWorld, world, posKey, CookingPotBlockBehavior.INVENTORY_SIZE);
        this.entity = entity;
        this.refreshFromEntity();
    }

    @Override
    protected void readFromEntity() {
        for (int i = 0; i < this.items.length; i++) {
            this.items[i] = normalize(BukkitItemManager.instance().wrap(this.entity.getInventorySlot(i)));
        }
    }

    @Override
    protected void writeToEntity() {
        for (int i = 0; i < this.items.length; i++) {
            this.entity.setInventorySlot(i, this.asBukkitStack(this.items[i]));
        }
        this.entity.tryMovePendingToOutput();
        CookingPotBlockBehavior.saveBlockEntityData(this.bukkitWorld, this.posKey);
        TickManager tickManager = this.plugin.getTickManager();
        if (tickManager != null && this.entity.hasStoredContents()) {
            tickManager.markActive(this.bukkitWorld, this.posKey, TickManager.BlockType.COOKING_POT);
        }
    }

    @Override
    public int[] getSlotsForFace(Direction direction) {
        return switch (direction) {
            case UP -> new int[]{0, 1, 2, 3, 4, 5};
            case DOWN, NORTH, SOUTH, EAST, WEST -> new int[]{CookingPotBlockBehavior.SLOT_CONTAINER, CookingPotBlockBehavior.SLOT_OUTPUT};
            default -> new int[0];
        };
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, net.momirealms.craftengine.core.item.Item stack, Direction direction) {
        return slot != CookingPotBlockBehavior.SLOT_OUTPUT;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, net.momirealms.craftengine.core.item.Item stack, Direction direction) {
        return true;
    }
}
