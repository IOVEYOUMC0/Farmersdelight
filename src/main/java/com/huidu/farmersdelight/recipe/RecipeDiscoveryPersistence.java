package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.route.Route;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Pending immutable snapshots outlive online cache entries and failed disk writes. */
final class RecipeDiscoveryPersistence {
    @FunctionalInterface
    interface Writer {
        void write(Path target, String contents) throws IOException;
    }

    private record Snapshot(long revision, Set<String> keys) {
    }

    private final Path file;
    private final Writer writer;
    private final java.util.concurrent.locks.ReentrantLock fileLock = new java.util.concurrent.locks.ReentrantLock();
    private final AtomicLong revision = new AtomicLong();
    private final Map<UUID, Snapshot> pending = new ConcurrentHashMap<>();

    RecipeDiscoveryPersistence(Path file) {
        this(file, (target, contents) -> ConfigFileUpdater.writeStringAtomically(target, contents, true));
    }

    RecipeDiscoveryPersistence(Path file, Writer writer) {
        this.file = file;
        this.writer = writer;
    }

    void stage(UUID playerId, Set<String> keys) {
        pending.put(playerId, new Snapshot(revision.incrementAndGet(), Set.copyOf(keys)));
    }

    Set<String> read(UUID playerId) throws IOException {
        Snapshot snapshot = pending.get(playerId);
        if (snapshot != null) {
            return snapshot.keys();
        }
        lockFile();
        try {
            snapshot = pending.get(playerId);
            if (snapshot != null) {
                return snapshot.keys();
            }
            var sequence = readDocument().getSequenceOrNull(Route.from(playerId.toString()));
            Set<String> keys = sequence == null ? Set.of() : sequence.getValues().stream()
                    .filter(String.class::isInstance).map(String.class::cast)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            snapshot = pending.get(playerId);
            return snapshot == null ? keys : snapshot.keys();
        } finally {
            fileLock.unlock();
        }
    }

    void flush() throws IOException {
        lockFile();
        try {
            if (pending.isEmpty()) {
                return;
            }
            Map<UUID, Snapshot> batch = new HashMap<>(pending);
            YamlDocument document = readDocument();
            batch.forEach((id, snapshot) -> {
                if (snapshot.keys().isEmpty()) {
                    document.removeSubNode(id.toString());
                } else {
                    document.set(Route.from(id.toString()), new ArrayList<>(new java.util.TreeSet<>(snapshot.keys())));
                }
            });
            writer.write(file, document.dumpToString());
            // A newer snapshot staged during this write must remain pending for the next flush.
            batch.forEach((id, snapshot) -> pending.remove(id, snapshot));
        } finally {
            fileLock.unlock();
        }
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    private void lockFile() throws IOException {
        try {
            fileLock.lockInterruptibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the discovery file writer", interrupted);
        }
    }

    private YamlDocument readDocument() throws IOException {
        try {
            YamlDocument document = Files.exists(file) ? SparrowYaml.create().load(file) : SparrowYaml.create().createDocument();
            for (var entry : document.getValues().entrySet()) {
                try {
                    UUID.fromString(String.valueOf(entry.getKey()));
                } catch (IllegalArgumentException metadataKey) {
                    continue;
                }
                if (!(entry.getValue() instanceof java.util.List<?> keys)
                        || keys.stream().anyMatch(key -> !(key instanceof String))) {
                    throw new IOException("Expected a recipe key list for player " + entry.getKey());
                }
            }
            return document;
        } catch (RuntimeException invalidYaml) {
            throw new IOException("Cannot read recipe discovery data: " + file.getFileName(), invalidYaml);
        }
    }
}
