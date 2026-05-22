package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.BlockPosKey;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.bukkit.world.BukkitContainer;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.WorldlyContainer;
import net.momirealms.craftengine.core.world.World;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

abstract class WorldlyContainerBridge implements BukkitContainer, WorldlyContainer {
    protected final FarmersDelightPlugin plugin;
    protected final World ceWorld;
    protected final org.bukkit.World bukkitWorld;
    protected final BlockPosKey posKey;
    protected final Item[] items;
    protected final List<HumanEntity> viewers = new ArrayList<>();
    private int maxStackSize = 99;
    private final Object nmsContainer;

    protected WorldlyContainerBridge(FarmersDelightPlugin plugin, World ceWorld, org.bukkit.World bukkitWorld, BlockPosKey posKey, int size) {
        this.plugin = plugin;
        this.ceWorld = ceWorld;
        this.bukkitWorld = bukkitWorld;
        this.posKey = posKey;
        this.items = new Item[size];
        Arrays.fill(this.items, Item.empty());
        this.nmsContainer = plugin.getCraftEngine().platform().createContainer(this);
    }

    public Object nmsContainer() {
        return this.nmsContainer;
    }

    public void refreshFromEntity() {
        this.readFromEntity();
    }

    protected abstract void readFromEntity();

    protected abstract void writeToEntity();

    protected Item normalize(Item item) {
        if (item == null || item.isEmpty() || item.count() <= 0) {
            return Item.empty();
        }
        return item.copyWithCount(item.count());
    }

    @Override
    public void onOpen(HumanEntity player) {
        this.viewers.add(player);
    }

    @Override
    public void onClose(HumanEntity player) {
        this.viewers.remove(player);
    }

    @Override
    public List<HumanEntity> getViewers() {
        return this.viewers;
    }

    @Override
    public InventoryHolder getOwner() {
        return null;
    }

    @Override
    public int containerSize() {
        return this.items.length;
    }

    @Override
    public boolean isEmpty() {
        for (Item item : this.items) {
            if (item != null && !item.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Item getItem(int slot) {
        return this.items[slot];
    }

    @Override
    public Item removeItem(int slot, int count) {
        Item item = this.items[slot];
        if (item == null || item.isEmpty()) {
            return Item.empty();
        }

        Item result;
        if (item.count() <= count) {
            result = item;
            this.items[slot] = Item.empty();
        } else {
            result = item.copyWithCount(count);
            item.shrink(count);
        }
        this.setChanged();
        return result;
    }

    @Override
    public Item removeItemNoUpdate(int slot) {
        Item item = this.items[slot];
        if (item == null || item.isEmpty()) {
            return Item.empty();
        }

        Item result;
        if (item.count() <= 1) {
            result = item;
            this.items[slot] = Item.empty();
        } else {
            result = item.copyWithCount(1);
            item.shrink(1);
        }
        return result;
    }

    @Override
    public void setItem(int slot, Item item) {
        this.items[slot] = normalize(item);
        if (!this.items[slot].isEmpty() && this.items[slot].count() > this.maxStackSize) {
            this.items[slot].count(this.maxStackSize);
        }
    }

    @Override
    public int maxStackSize() {
        return this.maxStackSize;
    }

    @Override
    public void setChanged() {
        this.writeToEntity();
    }

    @Override
    public boolean stillValid(Player player) {
        return player.canInteractPoint(this.position().toVec3d(), player.getCachedInteractionRange());
    }

    @Override
    public List<Item> contents() {
        return Arrays.asList(this.items);
    }

    @Override
    public void setMaxStackSize(int size) {
        this.maxStackSize = size;
    }

    @Override
    public WorldPosition position() {
        return new WorldPosition(this.ceWorld, this.posKey.x(), this.posKey.y(), this.posKey.z());
    }

    @Override
    public void clearContent() {
        Arrays.fill(this.items, Item.empty());
        this.setChanged();
    }

    protected org.bukkit.inventory.ItemStack asBukkitStack(Item item) {
        return item == null || item.isEmpty() ? null : ItemStackUtils.getBukkitStack(item.minecraftItem());
    }

    protected Item asCeItem(org.bukkit.inventory.ItemStack stack) {
        return normalize(net.momirealms.craftengine.bukkit.item.BukkitItemManager.instance().wrap(stack));
    }

    @Override
    public boolean canPlaceItem(int slot, Item item) {
        return true;
    }

    @Override
    public boolean canTakeItem(Object into, int slot, Item item) {
        return true;
    }

    @Override
    public int[] getSlotsForFace(Direction direction) {
        return new int[0];
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, Item stack, Direction direction) {
        return true;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, Item stack, Direction direction) {
        return true;
    }
}
