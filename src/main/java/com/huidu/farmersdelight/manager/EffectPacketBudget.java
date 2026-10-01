package com.huidu.farmersdelight.manager;

import java.util.concurrent.atomic.AtomicInteger;

final class EffectPacketBudget {
    private EffectPacketBudget() { }

    /** Reserves a complete effect without exceeding the chunk's logical packet allowance. */
    static boolean tryReserve(AtomicInteger used, int limit, int cost) {
        if (used == null || cost < 1) return false;
        int current;
        do {
            current = used.get();
            if (cost > limit || current > limit - cost) return false;
        } while (!used.compareAndSet(current, current + cost));
        return true;
    }
}
