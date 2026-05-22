package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.HopperInventorySearchEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HopperInteractionListener implements Listener {

    private static final int[] COOKING_POT_INGREDIENT_SLOTS = {0, 1, 2, 3, 4, 5};
    private static final int[] COOKING_POT_CONTAINER_SLOT = {CookingPotBlockBehavior.SLOT_CONTAINER};
    private static final int[] COOKING_POT_OUTPUT_SLOT = {CookingPotBlockBehavior.SLOT_OUTPUT};
    private static final int[] CUTTING_BOARD_SLOT = {0};

    private final FarmersDelightPlugin plugin;
    private final Map<HopperViewKey, HopperViewHolder> viewCache = new ConcurrentHashMap<>();

    public HopperInteractionListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperInventorySearch(HopperInventorySearchEvent event) {
        if (!plugin.isCookingPotHopperInteractionsEnabled() && !plugin.isCuttingBoardHopperInteractionsEnabled()) {
            return;
        }

        Block searchBlock = event.getSearchBlock();
        if (searchBlock == null) {
            return;
        }

        Inventory inventory = createView(event);
        if (inventory != null) {
            event.setInventory(inventory);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        HopperViewHolder source = holder(event.getSource());
        HopperViewHolder destination = holder(event.getDestination());
        if (source == null && destination == null) {
            return;
        }

        event.setCancelled(true);
        ItemStack item = cloneOrNull(event.getItem());
        if (item == null) {
            return;
        }

        if (source != null && destination != null) {
            moveBetweenCustomContainers(source, destination, item);
            return;
        }
        if (destination != null && source == null) {
            moveIntoCustomContainer(event.getSource(), destination, item);
            return;
        }
        if (source != null && destination == null) {
            moveOutOfCustomContainer(source, event.getDestination(), item);
        }
    }

    private Inventory createView(HopperInventorySearchEvent event) {
        Block block = event.getSearchBlock();
        if (plugin.isCookingPotHopperInteractionsEnabled()
                && CookingPotBlockBehavior.hasCookingPotBehavior(block.getWorld(), new BlockPosKey(block.getLocation()))) {
            return createCookingPotView(block, event.getContainerType());
        }
        if (plugin.isCuttingBoardHopperInteractionsEnabled()
                && CustomBlockUtils.hasBehavior(block, CuttingBoardBlockBehavior.class)) {
            return createCuttingBoardView(block);
        }
        return null;
    }

    private Inventory createCookingPotView(Block block, HopperInventorySearchEvent.ContainerType type) {
        World world = block.getWorld();
        BlockPosKey posKey = new BlockPosKey(block.getLocation());
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(world, posKey);
        if (entity == null) {
            CookingPotBlockBehavior.loadBlockEntity(world, posKey);
            entity = CookingPotBlockBehavior.getBlockEntity(world, posKey);
        }
        if (entity == null) {
            debug("Rejected cooking pot hopper view at " + format(block) + " because no entity is loaded.");
            return null;
        }

        int[] slots = cookingPotSlots(block, type);
        if (slots.length == 0) {
            debug("Rejected cooking pot hopper view at " + format(block) + " because slot mapping was empty.");
            return null;
        }

        HopperViewHolder holder = holder(ViewType.COOKING_POT, world, posKey, slots);
        refreshCookingPotView(holder, entity);
        return holder.getInventory();
    }

    private int[] cookingPotSlots(Block block, HopperInventorySearchEvent.ContainerType type) {
        if (type == HopperInventorySearchEvent.ContainerType.SOURCE) {
            return COOKING_POT_OUTPUT_SLOT;
        }

        BlockFace attachedFace = hopperFacing(block);
        if (attachedFace == BlockFace.UP) {
            return COOKING_POT_INGREDIENT_SLOTS;
        }
        if (attachedFace == BlockFace.DOWN || attachedFace == BlockFace.SELF) {
            return new int[0];
        }
        return COOKING_POT_CONTAINER_SLOT;
    }

    private Inventory createCuttingBoardView(Block block) {
        World world = block.getWorld();
        BlockPosKey posKey = new BlockPosKey(block.getLocation());
        CuttingBoardBlockEntity entity = CuttingBoardBlockBehavior.getBlockEntity(world, posKey);
        if (entity == null) {
            CuttingBoardBlockBehavior.loadBlockEntity(world, posKey);
            entity = CuttingBoardBlockBehavior.getBlockEntity(world, posKey);
        }
        if (entity == null) {
            debug("Rejected cutting board hopper view at " + format(block) + " because no entity is loaded.");
            return null;
        }

        HopperViewHolder holder = holder(ViewType.CUTTING_BOARD, world, posKey, CUTTING_BOARD_SLOT);
        refreshCuttingBoardView(holder, entity);
        return holder.getInventory();
    }

    private void moveIntoCustomContainer(Inventory source, HopperViewHolder destination, ItemStack item) {
        int moved = destination.type == ViewType.COOKING_POT
                ? insertIntoCookingPot(destination, item)
                : insertIntoCuttingBoard(destination, item);
        if (moved <= 0) {
            return;
        }
        removeSimilar(source, item, moved);
        refreshView(destination);
    }

    private void moveOutOfCustomContainer(HopperViewHolder source, Inventory destination, ItemStack requested) {
        int fit = fitAmount(destination, requested);
        if (fit <= 0) {
            return;
        }

        ItemStack extracted = source.type == ViewType.COOKING_POT
                ? extractFromCookingPot(source, Math.min(fit, requested.getAmount()))
                : extractFromCuttingBoard(source, Math.min(fit, requested.getAmount()));
        if (extracted == null || extracted.getType().isAir()) {
            return;
        }

        destination.addItem(extracted);
        refreshView(source);
    }

    private void moveBetweenCustomContainers(HopperViewHolder source, HopperViewHolder destination, ItemStack item) {
        int fit = fitAmount(destination.getInventory(), item);
        if (fit <= 0) {
            return;
        }

        ItemStack transfer = item.clone();
        transfer.setAmount(Math.min(transfer.getAmount(), fit));
        int moved = destination.type == ViewType.COOKING_POT
                ? insertIntoCookingPot(destination, transfer)
                : insertIntoCuttingBoard(destination, transfer);
        if (moved <= 0) {
            return;
        }

        ItemStack extracted = source.type == ViewType.COOKING_POT
                ? extractFromCookingPot(source, moved)
                : extractFromCuttingBoard(source, moved);
        if (extracted == null || extracted.getType().isAir()) {
            return;
        }

        refreshView(source);
        refreshView(destination);
    }

    private HopperViewHolder holder(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof HopperViewHolder hopperViewHolder) {
            return hopperViewHolder;
        }
        return null;
    }

    private int insertIntoCookingPot(HopperViewHolder holder, ItemStack item) {
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(holder.world, holder.posKey);
        if (entity == null) {
            return 0;
        }

        ItemStack remainder;
        if (holder.slots.length == COOKING_POT_CONTAINER_SLOT.length
                && holder.slots[0] == CookingPotBlockBehavior.SLOT_CONTAINER) {
            remainder = entity.insertContainerStack(item);
        } else {
            remainder = entity.insertIngredientStack(item);
        }

        int moved = movedAmount(item, remainder);
        if (moved > 0) {
            entity.tryMovePendingToOutput();
            saveCookingPot(holder, entity);
        }
        return moved;
    }

    private int insertIntoCuttingBoard(HopperViewHolder holder, ItemStack item) {
        CuttingBoardBlockEntity entity = CuttingBoardBlockBehavior.getBlockEntity(holder.world, holder.posKey);
        if (entity == null || entity.isItemCarved()) {
            return 0;
        }

        ItemStack stored = entity.getStoredItem();
        ItemStack incoming = item.clone();
        int moved = 0;
        if (stored == null || stored.getType().isAir()) {
            moved = 1;
            incoming.setAmount(1);
            entity.setStoredItem(incoming, holder.world, holder.posKey, facing(holder));
        } else if (stored.isSimilar(incoming) && stored.getAmount() < stored.getMaxStackSize()) {
            moved = 1;
            stored.setAmount(stored.getAmount() + 1);
            entity.setStoredItem(stored, holder.world, holder.posKey, facing(holder));
        }

        if (moved > 0) {
            CuttingBoardBlockBehavior.saveBlockEntityData(holder.world, holder.posKey);
        }
        return moved;
    }

    private ItemStack extractFromCookingPot(HopperViewHolder holder, int amount) {
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(holder.world, holder.posKey);
        if (entity == null) {
            return null;
        }

        ItemStack extracted = entity.takeMealPortionForDelivery(holder.world, amount);
        if (extracted != null && !extracted.getType().isAir()) {
            saveCookingPot(holder, entity);
        }
        return extracted;
    }

    private ItemStack extractFromCuttingBoard(HopperViewHolder holder, int amount) {
        CuttingBoardBlockEntity entity = CuttingBoardBlockBehavior.getBlockEntity(holder.world, holder.posKey);
        if (entity == null || !entity.hasItem()) {
            return null;
        }

        ItemStack stored = entity.getStoredItem();
        int moved = Math.min(amount, stored.getAmount());
        ItemStack extracted = stored.clone();
        extracted.setAmount(moved);

        stored.setAmount(stored.getAmount() - moved);
        if (stored.getAmount() <= 0) {
            entity.clearItem();
        } else {
            entity.setStoredItem(stored, holder.world, holder.posKey, facing(holder));
        }
        CuttingBoardBlockBehavior.saveBlockEntityData(holder.world, holder.posKey);
        return extracted;
    }

    private void saveCookingPot(HopperViewHolder holder, CookingPotBlockEntity entity) {
        CookingPotBlockBehavior.saveBlockEntityData(holder.world, holder.posKey);
        TickManager tickManager = plugin.getTickManager();
        if (tickManager != null && entity.hasStoredContents()) {
            tickManager.markActive(holder.world, holder.posKey, TickManager.BlockType.COOKING_POT);
        }
    }

    private void refreshView(HopperViewHolder holder) {
        if (holder == null) {
            return;
        }
        if (holder.type == ViewType.COOKING_POT) {
            CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(holder.world, holder.posKey);
            if (entity != null) {
                refreshCookingPotView(holder, entity);
            }
            return;
        }
        CuttingBoardBlockEntity entity = CuttingBoardBlockBehavior.getBlockEntity(holder.world, holder.posKey);
        if (entity != null) {
            refreshCuttingBoardView(holder, entity);
        }
    }

    private void refreshCookingPotView(HopperViewHolder holder, CookingPotBlockEntity entity) {
        Inventory inventory = holder.getInventory();
        inventory.clear();
        for (int i = 0; i < holder.slots.length && i < inventory.getSize(); i++) {
            inventory.setItem(i, entity.getInventorySlot(holder.slots[i]));
        }
    }

    private void refreshCuttingBoardView(HopperViewHolder holder, CuttingBoardBlockEntity entity) {
        Inventory inventory = holder.getInventory();
        inventory.clear();
        inventory.setItem(0, entity.getStoredItem());
    }

    private HopperViewHolder holder(ViewType type, World world, BlockPosKey posKey, int[] slots) {
        HopperViewKey key = new HopperViewKey(type, world.getUID(), posKey, Arrays.toString(slots));
        HopperViewHolder holder = viewCache.computeIfAbsent(key, ignored -> new HopperViewHolder(type, world, posKey, slots.clone()));
        if (holder.inventory == null) {
            holder.inventory = plugin.getServer().createInventory(holder, 9);
        }
        return holder;
    }

    private BlockFace facing(HopperViewHolder holder) {
        return CustomBlockUtils.getFacing(holder.posKey.toLocation(holder.world).getBlock());
    }

    private int movedAmount(ItemStack original, ItemStack remainder) {
        if (original == null) {
            return 0;
        }
        int remaining = remainder == null || remainder.getType().isAir() ? 0 : remainder.getAmount();
        return Math.max(0, original.getAmount() - remaining);
    }

    private int fitAmount(Inventory inventory, ItemStack item) {
        if (inventory == null || item == null || item.getType().isAir()) {
            return 0;
        }

        int remaining = item.getAmount();
        for (ItemStack stack : inventory.getStorageContents()) {
            if (remaining <= 0) {
                break;
            }
            if (stack == null || stack.getType().isAir()) {
                remaining -= Math.min(remaining, item.getMaxStackSize());
                continue;
            }
            if (!stack.isSimilar(item)) {
                continue;
            }
            remaining -= Math.min(remaining, stack.getMaxStackSize() - stack.getAmount());
        }
        return item.getAmount() - Math.max(0, remaining);
    }

    private void removeSimilar(Inventory inventory, ItemStack item, int amount) {
        if (inventory == null || item == null || amount <= 0) {
            return;
        }

        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir() || !stack.isSimilar(item)) {
                continue;
            }

            int removed = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - removed);
            remaining -= removed;
            contents[i] = stack.getAmount() <= 0 ? null : stack;
        }
        inventory.setStorageContents(contents);
    }

    private BlockFace hopperFacing(Block searchBlock) {
        for (BlockFace face : BlockFace.values()) {
            if (!face.isCartesian()) {
                continue;
            }
            Block relative = searchBlock.getRelative(face);
            if (relative.getType() != Material.HOPPER) {
                continue;
            }
            if (relative.getBlockData() instanceof org.bukkit.block.data.type.Hopper hopperData
                    && hopperData.getFacing() == face.getOppositeFace()) {
                return face;
            }
        }
        return BlockFace.SELF;
    }

    private ItemStack cloneOrNull(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return null;
        }
        return stack.clone();
    }

    private void debug(String message) {
        if (plugin.isDebugEnabled("hopper") || plugin.isDebugEnabled("storage")) {
            plugin.getLogger().info("[hopper] " + message);
        }
    }

    private String format(Block block) {
        Location location = block.getLocation();
        return location.getWorld().getName() + ":" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private enum ViewType {
        COOKING_POT,
        CUTTING_BOARD
    }

    private record HopperViewKey(ViewType type, java.util.UUID worldId, BlockPosKey posKey, String slotsKey) {
    }

    private static final class HopperViewHolder implements InventoryHolder {
        private final ViewType type;
        private final World world;
        private final BlockPosKey posKey;
        private final int[] slots;
        private Inventory inventory;

        private HopperViewHolder(ViewType type, World world, BlockPosKey posKey, int[] slots) {
            this.type = type;
            this.world = world;
            this.posKey = posKey;
            this.slots = slots;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
