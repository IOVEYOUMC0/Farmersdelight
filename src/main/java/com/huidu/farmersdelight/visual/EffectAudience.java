package com.huidu.farmersdelight.visual;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable player positions published by entity threads and consumed by region/network threads. */
final class EffectAudience {
    record Position(long session, UUID world, double x, double y, double z) {
        boolean inRange(UUID sourceWorld, double sx, double sy, double sz, double radiusSquared) {
            if (!world.equals(sourceWorld)) return false;
            double dx = x - sx, dy = y - sy, dz = z - sz;
            return dx * dx + dy * dy + dz * dz <= radiusSquared;
        }
    }

    private final AtomicLong sessions = new AtomicLong();
    private final Map<UUID, Position> positions = new ConcurrentHashMap<>();

    void publish(UUID player, UUID world, double x, double y, double z) {
        positions.compute(player, (id, previous) -> {
            if (previous != null && previous.world().equals(world)
                    && previous.x() == x && previous.y() == y && previous.z() == z) return previous;
            long session = previous == null || !previous.world().equals(world)
                    ? sessions.incrementAndGet() : previous.session();
            return new Position(session, world, x, y, z);
        });
    }

    void invalidate(UUID player) { positions.remove(player); }
    Position get(UUID player) { return positions.get(player); }
    void clear() { positions.clear(); }
}
