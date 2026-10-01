package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EffectPacketBudgetTest {
    @Test void smokeAndFlameAreReservedTogether() {
        var used = new AtomicInteger(49);
        assertFalse(EffectPacketBudget.tryReserve(used, 50, 2));
        assertEquals(49, used.get());
        assertTrue(EffectPacketBudget.tryReserve(used, 50, 1));
        assertFalse(EffectPacketBudget.tryReserve(used, 50, 1));
        assertEquals(50, used.get());
    }

    @Test void zeroRemainingBudgetCannotBeSpentByTheSecondSlotEffect() {
        var used = new AtomicInteger(48);
        assertTrue(EffectPacketBudget.tryReserve(used, 50, 2));
        assertFalse(EffectPacketBudget.tryReserve(used, 50, 1));
        assertFalse(EffectPacketBudget.tryReserve(null, 50, 1));
        assertFalse(EffectPacketBudget.tryReserve(used, 50, 0));
        assertEquals(50, used.get());
    }
}
