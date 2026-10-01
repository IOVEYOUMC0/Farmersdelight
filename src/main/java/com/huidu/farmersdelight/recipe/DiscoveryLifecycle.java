package com.huidu.farmersdelight.recipe;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A removed session never reuses a token from an earlier connection. */
final class DiscoveryLifecycle {
    private long sequence;
    private final Map<UUID, Long> versions = new HashMap<>();

    synchronized long mark(UUID player) {
        long token = ++sequence;
        versions.put(player, token);
        return token;
    }

    synchronized boolean current(UUID player, long token) {
        return java.util.Objects.equals(versions.get(player), token);
    }

    synchronized void retire(UUID player, long token) {
        versions.remove(player, token);
    }
}
