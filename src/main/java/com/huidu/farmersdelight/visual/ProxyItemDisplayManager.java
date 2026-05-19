package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyItemDisplayManager implements Listener, ItemDisplayManager {

    private static final double VIEW_DISTANCE = 64.0D;
    private static final double VIEW_DISTANCE_SQUARED = VIEW_DISTANCE * VIEW_DISTANCE;

    private final FarmersDelightPlugin plugin;
    private final BukkitNetworkManager networkManager;
    private final AtomicInteger nextEntityId = new AtomicInteger(2_000_000);
    private final Map<Integer, ProxyItemDisplay> displays = new ConcurrentHashMap<>();
    private BukkitTask syncTask;

    public ProxyItemDisplayManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        BukkitCraftEngine craftEngine = BukkitCraftEngine.instance();
        if (craftEngine != null) {
            this.networkManager = craftEngine.networkManager();
        } else {
            this.networkManager = null;
        }
        if (isAvailable()) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            startSyncTask();
        }
    }

    @Override
    public boolean isAvailable() {
        return networkManager != null;
    }

    @Override
    public int createDisplay(DisplaySpec spec) {
        if (!isAvailable() || spec == null || spec.location() == null || spec.location().getWorld() == null) {
            return -1;
        }

        DisplaySpec normalizedSpec = normalize(spec);
        int entityId = nextEntityId.getAndIncrement();
        ProxyItemDisplay display = new ProxyItemDisplay(entityId, UUID.randomUUID(), normalizedSpec);
        displays.put(entityId, display);
        syncDisplay(display);
        return entityId;
    }

    @Override
    public void destroyDisplay(int entityId) {
        ProxyItemDisplay removed = displays.remove(entityId);
        if (removed != null) {
            destroyForAllViewers(removed);
        }
    }

    @Override
    public void cleanupWorld(UUID worldId) {
        if (worldId == null) {
            return;
        }

        List<Integer> toRemove = new ArrayList<>();
        for (Map.Entry<Integer, ProxyItemDisplay> entry : displays.entrySet()) {
            Location location = entry.getValue().spec.location();
            if (location.getWorld() != null && location.getWorld().getUID().equals(worldId)) {
                toRemove.add(entry.getKey());
            }
        }

        for (Integer entityId : toRemove) {
            destroyDisplay(entityId);
        }
    }

    @Override
    public void cleanup() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }

        for (Integer entityId : new ArrayList<>(displays.keySet())) {
            destroyDisplay(entityId);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> syncPlayer(event.getPlayer()), 5L);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> syncPlayer(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        for (ProxyItemDisplay display : displays.values()) {
            display.viewers.remove(playerId);
        }
    }

    private void startSyncTask() {
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::syncAll, 20L, 20L);
    }

    private void syncAll() {
        if (!isAvailable()) {
            return;
        }

        for (ProxyItemDisplay display : displays.values()) {
            syncDisplay(display);
        }
    }

    private void syncPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        for (ProxyItemDisplay display : displays.values()) {
            if (shouldViewerSeeDisplay(player, display)) {
                if (!display.viewers.contains(player.getUniqueId())) {
                    spawnForViewer(player, display);
                }
            } else if (display.viewers.contains(player.getUniqueId())) {
                destroyForViewer(player, display);
            }
        }
    }

    private void syncDisplay(ProxyItemDisplay display) {
        World world = display.spec.location().getWorld();
        if (world == null || !world.isChunkLoaded(display.spec.location().getBlockX() >> 4, display.spec.location().getBlockZ() >> 4)) {
            destroyForAllViewers(display);
            return;
        }

        Set<UUID> desiredViewers = new HashSet<>();
        for (Player player : world.getPlayers()) {
            if (!shouldViewerSeeDisplay(player, display)) {
                continue;
            }

            desiredViewers.add(player.getUniqueId());
            if (!display.viewers.contains(player.getUniqueId())) {
                spawnForViewer(player, display);
            }
        }

        for (UUID viewerId : new HashSet<>(display.viewers)) {
            if (!desiredViewers.contains(viewerId)) {
                Player player = Bukkit.getPlayer(viewerId);
                if (player != null) {
                    destroyForViewer(player, display);
                } else {
                    display.viewers.remove(viewerId);
                }
            }
        }
    }

    private boolean shouldViewerSeeDisplay(Player player, ProxyItemDisplay display) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        Location location = display.spec.location();
        if (location.getWorld() == null || !Objects.equals(player.getWorld(), location.getWorld())) {
            return false;
        }

        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }

        return player.getLocation().distanceSquared(location) <= VIEW_DISTANCE_SQUARED;
    }

    private void spawnForViewer(Player player, ProxyItemDisplay display) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user == null || !user.isOnline()) {
                return;
            }
            user.sendPackets(List.of(createSpawnPacket(display), createMetadataPacket(display)), false);
            display.viewers.add(player.getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to spawn CE proxy item display " + display.entityId + " for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void destroyForViewer(Player player, ProxyItemDisplay display) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user != null && user.isOnline()) {
                user.sendPacket(createDestroyPacket(display.entityId), false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to destroy CE proxy item display " + display.entityId + " for " + player.getName() + ": " + e.getMessage());
        } finally {
            display.viewers.remove(player.getUniqueId());
        }
    }

    private void destroyForAllViewers(ProxyItemDisplay display) {
        for (UUID viewerId : new HashSet<>(display.viewers)) {
            Player player = Bukkit.getPlayer(viewerId);
            if (player != null) {
                destroyForViewer(player, display);
            } else {
                display.viewers.remove(viewerId);
            }
        }
    }

    private Object createSpawnPacket(ProxyItemDisplay display) {
        Location location = display.spec.location();
        Object entityType = resolveItemDisplayEntityType();
        Object deltaMovement = resolveZeroVec3();
        return invokeStaticProxy(
                "net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy",
                "newInstance",
                display.entityId,
                display.entityUuid,
                location.getX(),
                location.getY(),
                location.getZ(),
                0.0F,
                0.0F,
                entityType,
                0,
                deltaMovement,
                0.0D
        );
    }

    private Object resolveItemDisplayEntityType() {
        return getStaticField("net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypeProxy", "ITEM_DISPLAY");
    }

    private Object resolveZeroVec3() {
        return getStaticField("net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy", "ZERO");
    }

    private Object createMetadataPacket(ProxyItemDisplay display) {
        World world = display.spec.location().getWorld();
        if (world == null) {
            return createEntityDataPacket(display.entityId, List.of());
        }

        Location templateLocation = getTemplateLocation(display.spec.location());
        ItemDisplay temporaryDisplay = world.spawn(templateLocation, ItemDisplay.class, entity -> {
            applySpec(entity, display.spec);
            entity.setVisibleByDefault(false);
            entity.setPersistent(false);
        });

        try {
            Object handle = invokeStaticProxy(
                    "net.momirealms.craftengine.proxy.bukkit.craftbukkit.entity.CraftEntityProxy",
                    "getEntity",
                    temporaryDisplay
            );
            Object entityData = invokeStaticProxy(
                    "net.momirealms.craftengine.proxy.minecraft.world.entity.EntityProxy",
                    "getEntityData",
                    handle
            );
            List<?> values = (List<?>) invokeStaticProxy(
                    "net.momirealms.craftengine.proxy.minecraft.network.syncher.SynchedEntityDataProxy",
                    "getNonDefaultValues",
                    entityData
            );
            return createEntityDataPacket(display.entityId, values);
        } finally {
            temporaryDisplay.remove();
        }
    }

    private Object createEntityDataPacket(int entityId, List<?> values) {
        return invokeStaticProxy(
                "net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy",
                "newInstance",
                entityId,
                values
        );
    }

    private Object createDestroyPacket(int entityId) {
        try {
            Class<?> intArrayListClass = Class.forName("it.unimi.dsi.fastutil.ints.IntArrayList");
            Object intList = intArrayListClass.getConstructor(int[].class).newInstance((Object) new int[]{entityId});
            return invokeStaticProxy(
                    "net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy",
                    "newInstance",
                    intList
            );
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create CE proxy remove-entity packet", e);
        }
    }

    private Location getTemplateLocation(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return origin.clone();
        }

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        double distanceToMin = Math.abs(origin.getY() - minY);
        double distanceToMax = Math.abs(maxY - origin.getY());
        double templateY = maxY;
        if (distanceToMin > distanceToMax) {
            templateY = minY;
        }
        return new Location(world, origin.getX(), templateY, origin.getZ(), origin.getYaw(), origin.getPitch());
    }

    private void applySpec(ItemDisplay display, DisplaySpec spec) {
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.setNoPhysics(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);
        display.setViewRange(1.0F);
        display.setShadowRadius(0.0F);
        display.setDisplayWidth(0.0F);
        display.setDisplayHeight(0.0F);
        display.setItemDisplayTransform(spec.itemTransform());
        display.setTransformation(spec.transformation());
        display.setItemStack(spec.itemStack());
    }

    private DisplaySpec normalize(DisplaySpec spec) {
        Location location = spec.location().clone();
        ItemStack itemStack = spec.itemStack().clone();
        itemStack.setAmount(1);
        return new DisplaySpec(location, itemStack, spec.itemTransform(), spec.transformation());
    }

    private Object getStaticField(String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className);
            Field field = clazz.getField(fieldName);
            return field.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve " + className + "." + fieldName, e);
        }
    }

    private Object invokeStaticProxy(String className, String methodName, Object... args) {
        try {
            Class<?> clazz = Class.forName(className);
            Object instance = clazz.getField("INSTANCE").get(null);
            Method method = findMethod(clazz, methodName, args.length);
            if (method == null) {
                throw new IllegalStateException("Unable to find proxy method: " + className + "." + methodName);
            }
            return method.invoke(instance, args);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to invoke proxy method: " + className + "." + methodName, e);
        }
    }

    private Method findMethod(Class<?> clazz, String methodName, int parameterCount) {
        for (Method method : clazz.getMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            if (method.getParameterCount() != parameterCount) {
                continue;
            }
            return method;
        }
        return null;
    }

    private static final class ProxyItemDisplay {
        private final int entityId;
        private final UUID entityUuid;
        private final DisplaySpec spec;
        private final Set<UUID> viewers = new HashSet<>();

        private ProxyItemDisplay(int entityId, UUID entityUuid, DisplaySpec spec) {
            this.entityId = entityId;
            this.entityUuid = entityUuid;
            this.spec = spec;
        }
    }
}


