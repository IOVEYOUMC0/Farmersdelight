package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

public class RopeClimbListener implements Listener {

    private static final String ROPE_BLOCK_ID = "farmersdelight:rope";
    private static final double ROPE_MIN = 7.0 / 16.0;
    private static final double ROPE_MAX = 9.0 / 16.0;
    private static final double CLIMB_SPEED = 0.2;
    private static final double DESCEND_SPEED = -0.1;

    private final FarmersDelightPlugin plugin;

    public RopeClimbListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || from == null) return;

        Player player = event.getPlayer();
        if (player.isInWater() || player.isFlying()) return;

        if (!isPlayerInsideRope(player)) return;

        player.setFallDistance(0);

        if (player.isSneaking()) {
            player.setVelocity(new Vector(0, Math.max(DESCEND_SPEED, player.getVelocity().getY()), 0));
            return;
        }

        if (to.getY() > from.getY()) {
            player.setVelocity(new Vector(0, CLIMB_SPEED, 0));
        }
    }

    private boolean isPlayerInsideRope(Player player) {
        double reach = plugin.getConfig().getDouble("rope.climb-reach", 0.5);
        Location loc = player.getLocation();
        int blockX = loc.getBlockX();
        int blockZ = loc.getBlockZ();
        double relX = loc.getX() - blockX;
        double relZ = loc.getZ() - blockZ;

        if (relX < ROPE_MIN - reach || relX > ROPE_MAX + reach
                || relZ < ROPE_MIN - reach || relZ > ROPE_MAX + reach) {
            return false;
        }

        for (int dy = 0; dy <= 1; dy++) {
            int blockY = loc.getBlockY() + dy;
            Block block = loc.getWorld().getBlockAt(blockX, blockY, blockZ);
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (state != null && !state.isEmpty() && CustomBlockUtils.hasId(state, ROPE_BLOCK_ID)) {
                return true;
            }
        }
        return false;
    }
}
