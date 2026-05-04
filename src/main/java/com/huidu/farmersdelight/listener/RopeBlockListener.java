package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.RopeBlockBehavior;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;

public class RopeBlockListener implements Listener {

    private final FarmersDelightPlugin plugin;

    public RopeBlockListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        World world = block.getWorld();
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        Bukkit.getScheduler().runTask(plugin, () ->
                RopeBlockBehavior.refreshAdjacentRopes(world, pos));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        World world = block.getWorld();
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        Bukkit.getScheduler().runTask(plugin, () ->
                RopeBlockBehavior.refreshAdjacentRopes(world, pos));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (event.getChangedType() != event.getBlock().getType()) {
            Block block = event.getBlock();
            World world = block.getWorld();
            BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
            Bukkit.getScheduler().runTask(plugin, () ->
                    RopeBlockBehavior.refreshAdjacentRopes(world, pos));
        }
    }
}
