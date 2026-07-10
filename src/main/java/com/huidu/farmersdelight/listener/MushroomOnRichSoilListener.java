package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.block.behavior.OrganicCompostBlockBehavior;
import com.huidu.farmersdelight.block.behavior.RichSoilBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Right-clicking a vanilla brown/red mushroom onto a rich_soil or organic_compost block places the
 * corresponding CE mushroom_colony on top. Vanilla's mushroom item rejects placement on these
 * surfaces at canPlaceOn (they aren't in #minecraft:mushroom_grow_block), so BlockPlaceEvent
 * never fires — we intercept PlayerInteractEvent instead to bypass that check and place the colony
 * manually. RichSoilBlockBehavior#randomTick still handles non-player placements (worldedit,
 * /setblock, structure spawns) as a fallback.
 *
 * <p>Colony block ids are read from the soil block's own behavior config
 * (brown-mushroom-colony / red-mushroom-colony), so a server that renames its
 * mushroom colony blocks only needs to update the YAML — no listener code change.
 */
public final class MushroomOnRichSoilListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) return;
        if (event.getBlockFace() != BlockFace.UP) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        ItemStack item = event.getItem();
        if (item == null || item.getAmount() <= 0) return;
        boolean brown = item.getType() == Material.BROWN_MUSHROOM;
        boolean red = item.getType() == Material.RED_MUSHROOM;
        if (!brown && !red) return;

        Key colonyId = resolveColonyId(clicked, brown);
        if (colonyId == null) return;

        Block target = clicked.getRelative(BlockFace.UP);
        if (!target.getType().isAir()) return;

        // Respect WorldGuard build protection (mirrors SkilletPlaceListener). Without this a player
        // with no build permission could place a colony block inside a protected region, since the
        // vanilla placement that WorldGuard would deny never fires for this custom-interaction path.
        if (!ProtectionCompat.canBuild(event.getPlayer(), target, ProtectionCompat.Feature.RICH_SOIL)) return;

        BlockDefinition colony = CraftEngineBlocks.byId(colonyId);
        if (colony == null) return;

        // Stop vanilla's placement attempt (which would fail at canPlaceOn anyway) and suppress its
        // failed-interaction feedback so the manual place + sound below is the only thing the player sees.
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        Location loc = target.getLocation().add(0.5, 0, 0.5);
        if (!CraftEngineBlocks.place(loc, colony.defaultState(), true)) return;

        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        target.getWorld().playSound(loc.add(0, 0.5, 0),
                Sound.BLOCK_GROWING_PLANT_CROP, 1.0F, 1.0F);
        event.getPlayer().swingHand(event.getHand());
    }

    /** Asks the clicked soil block's behavior which colony id corresponds to the held mushroom. */
    @Nullable
    private Key resolveColonyId(Block clicked, boolean brown) {
        RichSoilBlockBehavior richSoil = CustomBlockUtils.getBehavior(clicked, RichSoilBlockBehavior.class);
        if (richSoil != null) {
            return brown ? richSoil.getBrownMushroomColonyId() : richSoil.getRedMushroomColonyId();
        }
        OrganicCompostBlockBehavior compost = CustomBlockUtils.getBehavior(clicked, OrganicCompostBlockBehavior.class);
        if (compost != null) {
            return brown ? compost.getBrownMushroomColonyId() : compost.getRedMushroomColonyId();
        }
        return null;
    }
}
