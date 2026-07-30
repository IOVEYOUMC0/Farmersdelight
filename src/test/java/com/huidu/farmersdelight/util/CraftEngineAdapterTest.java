package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.core.world.Vec3d;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftEngineAdapterTest {

    private static final UUID PLAYER_ID = UUID.fromString("2b342f22-c6f2-4ae2-9481-1137f509442a");

    @Test
    void resolvesLegacyNetworkUserReturnType() {
        NetWorkUser expected = networkUser(NetWorkUser.class);

        assertSame(expected, CraftEngineAdapter.getOnlineUser(new LegacyNetworkManager(expected), PLAYER_ID));
    }

    @Test
    void resolvesNarrowedNetworkUserReturnType() {
        ModernNetworkUser expected = networkUser(ModernNetworkUser.class);

        assertSame(expected, CraftEngineAdapter.getOnlineUser(new ModernNetworkManager(expected), PLAYER_ID));
    }

    @Test
    void returnsNullWhenNetworkManagerHasNoLookupMethod() {
        assertNull(CraftEngineAdapter.getOnlineUser(new Object(), PLAYER_ID));
    }

    @Test
    void readsPlayerUuidThroughStableNetworkUserInterface() {
        assertEquals(PLAYER_ID, CraftEngineAdapter.playerUuid(networkUser(NetWorkUser.class)));
    }

    @Test
    void invokesPlayerInteractionMethodsWithoutLinkingPlayerType() {
        assertTrue(CraftEngineAdapter.canInteractPoint(
                new InteractionPlayer(),
                new Vec3d(1.0D, 2.0D, 3.0D)
        ));
    }

    private static <T extends NetWorkUser> T networkUser(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> method.getName().equals("uuid") ? PLAYER_ID : null
        ));
    }

    private static final class InteractionPlayer {
        public double getCachedInteractionRange() {
            return 4.5D;
        }

        public boolean canInteractPoint(Vec3d point, double range) {
            return Vec3d.ZERO.add(1.0D, 2.0D, 3.0D).equals(point) && range == 4.5D;
        }
    }

    private static final class LegacyNetworkManager {
        private final NetWorkUser user;

        private LegacyNetworkManager(NetWorkUser user) {
            this.user = user;
        }

        public NetWorkUser getOnlineUser(UUID playerId) {
            return user;
        }
    }

    private static final class ModernNetworkManager {
        private final ModernNetworkUser user;

        private ModernNetworkManager(ModernNetworkUser user) {
            this.user = user;
        }

        public ModernNetworkUser getOnlineUser(UUID playerId) {
            return user;
        }
    }

    private interface ModernNetworkUser extends NetWorkUser {
    }
}
