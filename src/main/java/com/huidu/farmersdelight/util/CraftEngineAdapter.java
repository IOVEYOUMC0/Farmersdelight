package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Small bridge helpers for CraftEngine callbacks.
 */
public final class CraftEngineAdapter {

    // CraftEngine 26.8 narrows BukkitNetworkManager#getOnlineUser's return type from NetWorkUser
    // to BukkitServerPlayer. Resolve it on the runtime class so both bytecode descriptors work.
    private static final ClassValue<Optional<Method>> ONLINE_USER_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            return Optional.ofNullable(findMethod(type, "getOnlineUser", UUID.class));
        }
    };

    // CraftEngine 26.8 changes core.entity.player.Player from an abstract class to an interface.
    // Direct calls cannot be binary-compatible in both directions because the JVM opcode changes
    // from invokevirtual to invokeinterface.
    private static final ClassValue<PlayerInteractionMethods> PLAYER_INTERACTION_METHODS = new ClassValue<>() {
        @Override
        protected PlayerInteractionMethods computeValue(Class<?> type) {
            return new PlayerInteractionMethods(
                    findMethod(type, "getCachedInteractionRange"),
                    findMethod(type, "canInteractPoint", Vec3d.class, double.class)
            );
        }
    };

    private CraftEngineAdapter() {
    }

    public static World toWorld(Object levelObj) {
        if (levelObj instanceof World world) {
            return world;
        }
        try {
            return LevelProxy.INSTANCE.getWorld(levelObj);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static BlockPos toBlockPos(Object posObj) {
        if (posObj instanceof BlockPos pos) {
            return pos;
        }
        try {
            return LocationUtils.fromBlockPos(posObj);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Reads the UUID through NetWorkUser, which remains an interface in both CraftEngine 26.7 and 26.8.
     */
    public static UUID playerUuid(Object player) {
        return player instanceof NetWorkUser networkUser ? networkUser.uuid() : null;
    }

    public static org.bukkit.entity.Player toBukkitPlayer(Object player) {
        UUID playerId = playerUuid(player);
        return playerId == null ? null : Bukkit.getPlayer(playerId);
    }

    public static boolean canInteractPoint(Object player, Vec3d point) {
        if (player == null || point == null) {
            return false;
        }

        PlayerInteractionMethods methods = PLAYER_INTERACTION_METHODS.get(player.getClass());
        if (methods.getCachedInteractionRange() == null || methods.canInteractPoint() == null) {
            return false;
        }

        try {
            Object range = methods.getCachedInteractionRange().invoke(player);
            if (!(range instanceof Number number)) {
                return false;
            }
            return Boolean.TRUE.equals(methods.canInteractPoint().invoke(player, point, number.doubleValue()));
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Looks up a CraftEngine network user without linking against the concrete manager's return type.
     * CraftEngine 26.7 and earlier return {@link NetWorkUser}; 26.8 returns BukkitServerPlayer.
     */
    public static NetWorkUser getOnlineUser(Object networkManager, UUID playerId) {
        if (networkManager == null || playerId == null) {
            return null;
        }

        Optional<Method> method = ONLINE_USER_METHODS.get(networkManager.getClass());
        if (method.isEmpty()) {
            return null;
        }

        try {
            Object user = method.get().invoke(networkManager, playerId);
            return user instanceof NetWorkUser networkUser ? networkUser : null;
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getMethod(name, parameterTypes);
            method.trySetAccessible();
            return method;
        } catch (NoSuchMethodException | SecurityException ignored) {
            return null;
        }
    }

    private record PlayerInteractionMethods(Method getCachedInteractionRange, Method canInteractPoint) {
    }
}
