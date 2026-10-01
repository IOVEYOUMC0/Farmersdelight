package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryLifecycleTest {
    @Test void reconnectDoesNotReuseRetiredToken() {
        var lifecycle = new DiscoveryLifecycle();
        UUID player = UUID.randomUUID();
        long firstJoin = lifecycle.mark(player);
        long quit = lifecycle.mark(player);
        lifecycle.retire(player, quit);
        long reconnect = lifecycle.mark(player);
        assertTrue(reconnect > quit);
        assertFalse(lifecycle.current(player, firstJoin));
        assertFalse(lifecycle.current(player, quit));
        assertTrue(lifecycle.current(player, reconnect));
    }

    @Test void staleQuitCannotRetireNewSessionAndAbsentZeroIsNotCurrent() {
        var lifecycle = new DiscoveryLifecycle();
        UUID player = UUID.randomUUID();
        assertFalse(lifecycle.current(player, 0));
        long staleQuit = lifecycle.mark(player);
        long newJoin = lifecycle.mark(player);
        lifecycle.retire(player, staleQuit);
        assertTrue(lifecycle.current(player, newJoin));
        lifecycle.retire(player, newJoin);
        assertFalse(lifecycle.current(player, newJoin));
    }
}
