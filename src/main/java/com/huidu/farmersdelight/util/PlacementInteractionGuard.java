package com.huidu.farmersdelight.util;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Suppresses both hands of a placing player's interaction only during the placement tick. */
public final class PlacementInteractionGuard {
    private final ConcurrentHashMap<UUID, Placements> players = new ConcurrentHashMap<>();

    private record Position(UUID worldId, BlockPosKey block) {
    }

    private static final class Placements {
        private final int tick;
        private final Set<Position> positions = ConcurrentHashMap.newKeySet();

        private Placements(int tick) {
            this.tick = tick;
        }
    }

    public void markPlaced(UUID playerId, UUID worldId, BlockPosKey block, int tick) {
        players.compute(playerId, (ignored, current) -> {
            Placements placements = current != null && current.tick == tick ? current : new Placements(tick);
            placements.positions.add(new Position(worldId, block));
            return placements;
        });
    }

    public boolean shouldSuppress(UUID playerId, UUID worldId, BlockPosKey block, int tick) {
        Placements placements = players.get(playerId);
        if (placements == null) {
            return false;
        }
        if (placements.tick != tick) {
            players.remove(playerId, placements);
            return false;
        }
        return placements.positions.contains(new Position(worldId, block));
    }

    public void removePlayer(UUID playerId) {
        players.remove(playerId);
    }

    public void clear() {
        players.clear();
    }
}
