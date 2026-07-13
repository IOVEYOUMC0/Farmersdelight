package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.block.behavior.OrganicCompostBlockBehavior;
import com.huidu.farmersdelight.block.behavior.RichSoilBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ProtectionCompat;
import org.bukkit.GameMode;
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

/**
 * Right-clicking a vanilla brown/red mushroom onto a rich_soil or organic_compost block plants the mushroom
 * on top. It does NOT create a mushroom colony directly — that mirrors the mod, where planting a mushroom on
 * rich soil never yields a colony on placement; the soil converts it later. The client sees the soil's
 * CraftEngine deceive-material (not a real mushroom_grow_block), so it won't place the mushroom itself and
 * BlockPlaceEvent never fires — we intercept PlayerInteractEvent and place the mushroom manually. rich_soil
 * and organic_compost are both tagged minecraft:mushroom_grow_block server-side, so the placed mushroom
 * survives; RichSoilBlockBehavior.randomTick then converts a mushroom sitting on rich soil to an age-0
 * colony that grows to maturity (a mushroom on compost survives until the compost ages into rich soil).
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
        Material mushroom;
        if (item.getType() == Material.BROWN_MUSHROOM) {
            mushroom = Material.BROWN_MUSHROOM;
        } else if (item.getType() == Material.RED_MUSHROOM) {
            mushroom = Material.RED_MUSHROOM;
        } else {
            return;
        }

        if (!isRichSoilOrCompost(clicked)) return;

        Block target = clicked.getRelative(BlockFace.UP);
        if (!target.getType().isAir()) return;

        // Respect WorldGuard build protection (mirrors SkilletPlaceListener). Without this a player with no
        // build permission could plant inside a protected region, since the vanilla placement that WorldGuard
        // would deny never fires for this custom-interaction path.
        if (!ProtectionCompat.canBuild(event.getPlayer(), target, ProtectionCompat.Feature.RICH_SOIL)) return;

        // Stop vanilla's placement attempt (which would fail at the client's canPlaceOn anyway) and suppress
        // its failed-interaction feedback so the manual mushroom place + sound below is all the player sees.
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        target.setType(mushroom);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        target.getWorld().playSound(target.getLocation().add(0.5, 0.5, 0.5),
                Sound.BLOCK_GROWING_PLANT_CROP, 1.0F, 1.0F);
        event.getPlayer().swingHand(event.getHand());
    }

    /** Whether the clicked block is a rich_soil or organic_compost CraftEngine block (a valid mushroom-growth soil). */
    private boolean isRichSoilOrCompost(Block clicked) {
        return CustomBlockUtils.getBehavior(clicked, RichSoilBlockBehavior.class) != null
                || CustomBlockUtils.getBehavior(clicked, OrganicCompostBlockBehavior.class) != null;
    }
}
