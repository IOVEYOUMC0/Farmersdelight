package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

public final class CookingPotBlockEntityController extends BlockEntityController {

    private static final String DATA_VERSION = "data_version";
    private static final String ITEMS = "items";
    private static final String SLOT_EXPERIENCE = "slot_experience";
    private static final String SLOT = "slot";
    private static final String EXPERIENCE = "experience";
    private static final String COOKING_PROGRESS = "cooking_progress";
    private static final String COOKING_DURATION = "cooking_duration";
    private static final String MEAL_CONTAINER = "meal_container";

    private final CookingPotBlockBehavior behavior;

    public CookingPotBlockEntityController(BlockEntity blockEntity, CookingPotBlockBehavior behavior) {
        super(blockEntity);
        this.behavior = behavior;
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        World world = getBukkitWorld();
        if (world == null) return;

        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(world, new BlockPosKey(this.blockEntity.pos));
        if (entity == null) return;

        tag.put(this.behavior.customDataKey, saveData(entity));
    }

    public static CompoundTag saveData(CookingPotBlockEntity entity) {
        CompoundTag data = new CompoundTag();
        data.putInt(DATA_VERSION, VersionHelper.WORLD_VERSION);
        synchronized (entity.getLock()) {
            data.put(ITEMS, ItemStackUtils.saveBukkitItemsAsListTag(entity.getInventoryInternal()));
            ListTag experienceTag = new ListTag();
            for (int i = 0; i < CookingPotBlockBehavior.INVENTORY_SIZE; i++) {
                double experience = entity.getSlotExperience(i);
                if (experience <= 0.0D) continue;
                CompoundTag slotTag = new CompoundTag();
                slotTag.putInt(SLOT, i);
                slotTag.putDouble(EXPERIENCE, experience);
                experienceTag.add(slotTag);
            }
            data.put(SLOT_EXPERIENCE, experienceTag);
        }
        data.putInt(COOKING_PROGRESS, entity.getCookingProgress());
        data.putInt(COOKING_DURATION, entity.getCookingDuration());
        ItemStack mealContainer = entity.getMealContainer();
        if (mealContainer != null && !mealContainer.getType().isAir()) {
            Tag mealContainerTag = ItemStackUtils.saveBukkitItemAsTag(mealContainer);
            if (mealContainerTag != null) {
                data.put(MEAL_CONTAINER, mealContainerTag);
            }
        }
        return data;
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        CompoundTag data = tag.getCompound(this.behavior.customDataKey);
        if (data == null) return;
        loadData(data);
    }

    @Override
    public void loadCustomDataFromItem(Item item) {
        Tag component = item.getComponentAsSparrowTag(DataComponentKeys.BLOCK_ENTITY_DATA);
        if (!(component instanceof CompoundTag tag)) return;
        CompoundTag data = tag.getCompound(this.behavior.customDataKey);
        if (data == null) return;
        loadData(data);
    }

    private void loadData(CompoundTag data) {
        World world = getBukkitWorld();
        if (world == null) return;

        BlockPosKey posKey = new BlockPosKey(this.blockEntity.pos);
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getOrCreateBlockEntity(posKey.toLocation(world));
        if (entity == null) return;

        int dataVersion = data.getInt(DATA_VERSION, Config.itemDataFixerUpperFallbackVersion());
        ItemStack[] items = ItemStackUtils.parseBukkitItems(Optional.ofNullable(data.getList(ITEMS)).orElseGet(ListTag::new),
                CookingPotBlockBehavior.INVENTORY_SIZE,
                dataVersion);
        for (int i = 0; i < CookingPotBlockBehavior.INVENTORY_SIZE; i++) {
            entity.setInventorySlot(i, items[i]);
            entity.setSlotExperience(i, 0.0D);
        }

        ListTag experienceTag = Optional.ofNullable(data.getList(SLOT_EXPERIENCE)).orElseGet(ListTag::new);
        for (int i = 0; i < experienceTag.size(); i++) {
            CompoundTag slotTag = experienceTag.getCompound(i);
            int slot = slotTag.getInt(SLOT, -1);
            if (slot < 0 || slot >= CookingPotBlockBehavior.INVENTORY_SIZE) continue;
            entity.setSlotExperience(slot, slotTag.getDouble(EXPERIENCE, 0.0D));
        }

        entity.setCookingProgress(data.getInt(COOKING_PROGRESS, 0));
        entity.setCookingDuration(data.getInt(COOKING_DURATION, 200));
        Tag mealContainerTag = data.get(MEAL_CONTAINER);
        if (mealContainerTag != null) {
            entity.setMealContainer(ItemStackUtils.parseBukkitItem(mealContainerTag, dataVersion));
        } else {
            entity.setMealContainer(null);
        }

        if (entity.hasStoredContents()) {
            FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
            if (plugin != null && plugin.getTickManager() != null) {
                plugin.getTickManager().markActive(world, posKey, TickManager.BlockType.COOKING_POT);
            }
        }
    }

    private World getBukkitWorld() {
        if (this.blockEntity.world == null || this.blockEntity.world.world() == null) {
            return null;
        }
        Object platformWorld = this.blockEntity.world.world().platformWorld();
        return platformWorld instanceof World world ? world : null;
    }
}
