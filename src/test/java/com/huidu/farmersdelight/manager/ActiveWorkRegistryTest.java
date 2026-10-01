package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ActiveWorkRegistryTest {
    private record Block(String world, int chunk, int position) { }
    private record Chunk(String world, int chunk) { }

    private ActiveWorkRegistry<Block, Chunk, String> registry() {
        return new ActiveWorkRegistry<>(block -> new Chunk(block.world(), block.chunk()), Chunk::world);
    }

    @Test void repeatedInteractionsDoNotGrowThePendingQueueOrChangeTheGeneration() {
        var registry = registry();
        Block block = new Block("world", 0, 1);
        for (int i = 0; i < 10_000; i++) registry.submit(block, true);
        assertEquals(new ActiveWorkRegistry.Counts(0, 1, 0), registry.counts());
        registry.drain(1);
        var dispatched = registry.select(1).getFirst();
        registry.submit(block, true);
        assertEquals(0, registry.counts().additions());
        assertTrue(registry.isCurrent(dispatched));
    }

    @Test void removalInvalidatesAlreadyDispatchedWorkBeforeTheGlobalDrain() {
        var registry = registry();
        Block block = new Block("world", 0, 1);
        registry.submit(block, true);
        registry.drain(1);
        var old = registry.select(1).getFirst();
        registry.submit(block, false);
        assertFalse(registry.isCurrent(old));
        registry.drain(1);
        assertTrue(registry.isIdle());
    }

    @Test void removeAndReactivateResetsTimeAndRejectsThePreviousGeneration() {
        var registry = registry();
        Block block = new Block("world", 0, 1);
        registry.submit(block, true);
        registry.drain(1);
        var old = registry.select(1).getFirst();
        registry.submit(block, false);
        registry.submit(block, true);
        assertFalse(registry.isCurrent(old));
        assertFalse(registry.isCurrent(registry.select(1).getFirst()));
        var changes = registry.drain(1);
        assertTrue(changes.getFirst().active());
        assertTrue(changes.getFirst().reset());
        assertTrue(registry.isCurrent(registry.select(1).getFirst()));
    }

    @Test void chunkRetirementIncludesUndrainedAdditionsWithoutRemovingOtherChunks() {
        var registry = registry();
        Block live = new Block("world", 0, 1);
        Block pending = new Block("world", 0, 2);
        Block neighbor = new Block("world", 1, 1);
        Block otherWorld = new Block("other", 0, 1);
        registry.submit(live, true);
        registry.drain(1);
        var old = registry.select(1).getFirst();
        for (Block block : List.of(pending, neighbor, otherWorld)) registry.submit(block, true);
        registry.retireGroup(new Chunk("world", 0));
        assertFalse(registry.isCurrent(old));
        registry.drain(100);
        assertEquals(Set.of(neighbor, otherWorld), keys(registry.select(100)));
    }

    @Test void worldRetirementAndStopDoNotLeaveStrongReferencesInTheIndexes() {
        var registry = registry();
        for (int i = 0; i < 10; i++) registry.submit(new Block("world", i, i), true);
        registry.submit(new Block("other", 0, 0), true);
        registry.drain(100);
        registry.retireWorld("world");
        registry.drain(100);
        assertEquals(1, registry.counts().active());
        var old = registry.select(1).getFirst();
        registry.clear();
        registry.submit(old.key(), true);
        registry.drain(1);
        assertFalse(registry.isCurrent(old));
        assertTrue(registry.isCurrent(registry.select(1).getFirst()));
    }

    @Test void rotationReachesEveryBlockWithinTheBudgetAndSurvivesSwapRemoval() {
        var registry = registry();
        Set<Block> expected = new HashSet<>();
        for (int i = 0; i < 1003; i++) {
            Block block = new Block("world", i / 64, i);
            expected.add(block);
            registry.submit(block, true);
        }
        assertEquals(128, registry.drain(128).size());
        registry.drain(2000);
        Set<Block> visited = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            var selected = registry.select(17);
            assertEquals(17, selected.size());
            visited.addAll(keys(selected));
        }
        assertEquals(expected, visited);
        for (Block block : expected) if (block.position() % 3 == 0) registry.submit(block, false);
        registry.drain(2000);
        expected.removeIf(block -> block.position() % 3 == 0);
        visited.clear();
        for (int i = 0; i < 60; i++) visited.addAll(keys(registry.select(17)));
        assertEquals(expected, visited);
        assertEquals(0, registry.select(0).size());
    }

    @Test void inactiveUnknownKeysAreIgnoredAndPendingCountsFollowTheLatestState() {
        var registry = registry();
        Block block = new Block("world", 0, 1);
        registry.submit(block, false);
        assertTrue(registry.isIdle());
        registry.submit(block, true);
        registry.submit(block, false);
        assertEquals(new ActiveWorkRegistry.Counts(0, 0, 1), registry.counts());
        registry.drain(0);
        assertEquals(1, registry.counts().removals());
        registry.drain(1);
        assertTrue(registry.isIdle());
    }

    @Test void reactivationUsesTheReplacementContextEvenWhenKeysAreEqual() {
        class ContextKey {
            final Object context;
            ContextKey(Object context) { this.context = context; }
            @Override public boolean equals(Object other) { return other instanceof ContextKey; }
            @Override public int hashCode() { return 1; }
        }
        var registry = new ActiveWorkRegistry<ContextKey, String, String>(key -> "chunk", group -> "world");
        ContextKey original = new ContextKey(new Object());
        ContextKey replacement = new ContextKey(new Object());
        registry.submit(original, true);
        registry.drain(1);
        var old = registry.select(1).getFirst();
        registry.retireWorld("world");
        registry.submit(replacement, true);
        registry.drain(1);
        assertSame(replacement, registry.select(1).getFirst().key());
        assertFalse(registry.isCurrent(old));
    }

    @Test void regionSubmissionsAndGlobalDrainingCanRunConcurrently() throws Exception {
        var registry = registry();
        CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(3)) {
            var first = threads.submit(() -> submitBurst(registry, 0, start));
            var second = threads.submit(() -> submitBurst(registry, 1, start));
            var drain = threads.submit(() -> {
                try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
                for (int i = 0; i < 2000; i++) {
                    registry.drain(32);
                    for (var work : registry.select(16)) registry.isCurrent(work);
                }
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            drain.get(10, TimeUnit.SECONDS);
        }
        registry.drain(2000);
        assertEquals(200, registry.counts().active());
        assertEquals(200, keys(registry.select(2000)).size());
        registry.retireWorld("world");
        registry.drain(2000);
        assertTrue(registry.isIdle());
    }

    private void submitBurst(ActiveWorkRegistry<Block, Chunk, String> registry, int chunk, CountDownLatch start) {
        try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
        for (int i = 0; i < 3000; i++) {
            Block block = new Block("world", chunk, i % 100);
            registry.submit(block, false);
            registry.submit(block, true);
        }
    }

    private Set<Block> keys(List<ActiveWorkRegistry.Selection<Block>> selected) {
        Set<Block> keys = new HashSet<>();
        for (var work : selected) keys.add(work.key());
        return keys;
    }
}
