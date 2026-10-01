package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RecipeDiscoveryPersistenceTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();

    @Test void repeatedSaveAndRestartKeepAllPlayers() throws Exception {
        Path file = directory.resolve("data.yml");
        var store = new RecipeDiscoveryPersistence(file);
        UUID other = UUID.randomUUID();
        store.stage(player, Set.of("pot first"));
        store.flush();
        store.stage(player, Set.of("pot first", "pot second"));
        store.stage(other, Set.of("board third"));
        store.flush();
        var restarted = new RecipeDiscoveryPersistence(file);
        assertEquals(Set.of("pot first", "pot second"), restarted.read(player));
        assertEquals(Set.of("board third"), restarted.read(other));
        assertFalse(store.hasPending());
    }

    @Test void failedWriteRetainsSnapshotForReconnectAndRetry() throws Exception {
        Path file = directory.resolve("data.yml");
        AtomicBoolean fail = new AtomicBoolean(true);
        var store = new RecipeDiscoveryPersistence(file, (target, content) -> {
            if (fail.get()) throw new IOException("disk unavailable");
            ConfigFileUpdater.writeStringAtomically(target, content, true);
        });
        store.stage(player, Set.of("pot new"));
        assertThrows(IOException.class, store::flush);
        assertTrue(store.hasPending());
        assertEquals(Set.of("pot new"), store.read(player));
        fail.set(false);
        store.flush();
        assertEquals(Set.of("pot new"), new RecipeDiscoveryPersistence(file).read(player));
    }

    @Test void newerSnapshotDuringWriteIsNotAcknowledgedByOldFlush() throws Exception {
        Path file = directory.resolve("data.yml");
        var holder = new RecipeDiscoveryPersistence[1];
        AtomicBoolean first = new AtomicBoolean(true);
        holder[0] = new RecipeDiscoveryPersistence(file, (target, content) -> {
            if (first.getAndSet(false)) holder[0].stage(player, Set.of("pot latest"));
            ConfigFileUpdater.writeStringAtomically(target, content, true);
        });
        var store = holder[0];
        store.stage(player, Set.of("pot previous"));
        store.flush();
        assertTrue(store.hasPending());
        assertEquals(Set.of("pot latest"), store.read(player));
        store.flush();
        assertEquals(Set.of("pot latest"), new RecipeDiscoveryPersistence(file).read(player));
    }

    @Test void emptySnapshotPersistsLockAllAndPreservesOtherPlayer() throws Exception {
        var store = new RecipeDiscoveryPersistence(directory.resolve("data.yml"));
        UUID other = UUID.randomUUID();
        store.stage(player, Set.of("pot old"));
        store.stage(other, Set.of("board keep"));
        store.flush();
        store.stage(player, Set.of());
        store.flush();
        var restarted = new RecipeDiscoveryPersistence(directory.resolve("data.yml"));
        assertEquals(Set.of(), restarted.read(player));
        assertEquals(Set.of("board keep"), restarted.read(other));
    }

    @Test void malformedFileIsNeverOverwrittenWithEmptyFallback() throws Exception {
        Path file = directory.resolve("data.yml");
        String broken = "players: [unterminated\n";
        Files.writeString(file, broken);
        var store = new RecipeDiscoveryPersistence(file);
        store.stage(player, Set.of("pot keep"));
        assertThrows(IOException.class, store::flush);
        assertEquals(broken, Files.readString(file));
        assertTrue(store.hasPending());
    }

    @Test void capturesIndependentImmutableSnapshot() throws Exception {
        var store = new RecipeDiscoveryPersistence(directory.resolve("data.yml"));
        var mutable = new java.util.HashSet<>(Set.of("pot captured"));
        store.stage(player, mutable);
        mutable.clear();
        assertEquals(Set.of("pot captured"), store.read(player));
    }

    @Test void wrongPlayerDataTypeIsNotSilentlyOverwritten() throws Exception {
        Path file = directory.resolve("data.yml");
        String original = player + ": wrong-type\n";
        Files.writeString(file, original);
        var store = new RecipeDiscoveryPersistence(file);
        assertThrows(IOException.class, () -> store.read(player));
        store.stage(player, Set.of("pot keep"));
        assertThrows(IOException.class, store::flush);
        assertEquals(original, Files.readString(file));
    }

    @Test void waitingWriterCanBeInterruptedWithoutDroppingPendingData() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var store = new RecipeDiscoveryPersistence(directory.resolve("data.yml"), (target, content) -> {
            entered.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("test timeout");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            }
            throw new IOException("simulated failed write");
        });
        store.stage(player, Set.of("pot pending"));
        Thread writer = Thread.ofVirtual().start(() -> {
            try { store.flush(); } catch (IOException expected) { }
        });
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Thread waiter = Thread.ofVirtual().start(() -> {
            try { store.flush(); } catch (IOException expected) { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            waiter.interrupt();
            waiter.join(5000);
            assertFalse(waiter.isAlive());
            assertTrue(interrupted.get());
            assertTrue(store.hasPending());
        } finally {
            release.countDown();
            writer.join(5000);
        }
    }
}
