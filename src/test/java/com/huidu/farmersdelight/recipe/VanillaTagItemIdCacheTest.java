package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VanillaTagItemIdCacheTest {
    @Test void reloadDoesNotBlockOnAnOldLookupOrLetItsResultPopulateTheNewCache() throws Exception {
        Key key = Key.of("minecraft:test");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Set<String>> members = new AtomicReference<>(Set.of("minecraft:old"));
        AtomicInteger calls = new AtomicInteger();
        var cache = new VanillaTagItemIdCache(tag -> {
            Set<String> captured = members.get();
            if (calls.getAndIncrement() == 0) {
                reading.countDown();
                try { release.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
            }
            return captured;
        });
        try (var threads = Executors.newSingleThreadExecutor()) {
            var oldLookup = threads.submit(() -> cache.getIds(key));
            try {
                assertTrue(reading.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(2), cache::clear);
                members.set(Set.of("minecraft:new"));
            } finally {
                release.countDown();
            }
            assertEquals(Set.of("minecraft:old"), oldLookup.get(5, TimeUnit.SECONDS));
            assertEquals(Set.of("minecraft:new"), cache.getIds(key));
            assertEquals(Set.of("minecraft:new"), cache.getIds(key));
            assertEquals(2, calls.get());
        }
    }

    @Test void cachedMembershipIsImmutableAndNullTagsDoNotCallCraftEngine() {
        Set<String> source = new HashSet<>(Set.of("minecraft:apple"));
        AtomicInteger calls = new AtomicInteger();
        var cache = new VanillaTagItemIdCache(tag -> { calls.incrementAndGet(); return source; });
        assertTrue(cache.getIds(null).isEmpty());
        assertEquals(0, calls.get());
        var ids = cache.getIds(Key.of("minecraft:fruit"));
        source.clear();
        assertEquals(Set.of("minecraft:apple"), ids);
        assertThrows(UnsupportedOperationException.class, ids::clear);
    }
}
