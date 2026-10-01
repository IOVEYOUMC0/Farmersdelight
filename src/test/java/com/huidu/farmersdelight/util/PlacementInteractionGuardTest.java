package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementInteractionGuardTest {
    private final PlacementInteractionGuard guard = new PlacementInteractionGuard();
    private final UUID player = UUID.randomUUID();
    private final UUID world = UUID.randomUUID();
    private final BlockPosKey pot = new BlockPosKey(4, 64, 8);

    @Test
    void suppressesRepeatedInteractionsForBothHandsOnlyDuringPlacementTick() {
        guard.markPlaced(player, world, pot, 10);

        assertTrue(guard.shouldSuppress(player, world, pot, 10));
        assertTrue(guard.shouldSuppress(player, world, pot, 10));
        assertFalse(guard.shouldSuppress(player, world, pot, 11));
    }

    @Test
    void otherPlayersAndOtherPotsRemainUsableImmediately() {
        guard.markPlaced(player, world, pot, 10);

        assertFalse(guard.shouldSuppress(UUID.randomUUID(), world, pot, 10));
        assertFalse(guard.shouldSuppress(player, UUID.randomUUID(), pot, 10));
        assertFalse(guard.shouldSuppress(player, world, new BlockPosKey(5, 64, 8), 10));
        assertTrue(guard.shouldSuppress(player, world, pot, 10));
    }

    @Test
    void protectsEveryPlacementInOneTickAndRetiresThePreviousTick() {
        BlockPosKey second = new BlockPosKey(5, 64, 8);
        guard.markPlaced(player, world, pot, 10);
        guard.markPlaced(player, world, second, 10);
        assertTrue(guard.shouldSuppress(player, world, pot, 10));
        assertTrue(guard.shouldSuppress(player, world, second, 10));

        guard.markPlaced(player, world, second, 11);
        assertFalse(guard.shouldSuppress(player, world, pot, 11));
        assertTrue(guard.shouldSuppress(player, world, second, 11));
    }

    @Test
    void quitAndDisableDiscardPendingProtection() {
        UUID secondPlayer = UUID.randomUUID();
        guard.markPlaced(player, world, pot, 10);
        guard.markPlaced(secondPlayer, world, pot, 10);
        guard.removePlayer(player);
        assertFalse(guard.shouldSuppress(player, world, pot, 10));
        assertTrue(guard.shouldSuppress(secondPlayer, world, pot, 10));

        guard.clear();
        assertFalse(guard.shouldSuppress(secondPlayer, world, pot, 10));
    }

    @Test
    void tickCounterWrapDoesNotReintroduceTheCooldown() {
        guard.markPlaced(player, world, pot, Integer.MAX_VALUE);
        assertFalse(guard.shouldSuppress(player, world, pot, Integer.MIN_VALUE));
    }
}
