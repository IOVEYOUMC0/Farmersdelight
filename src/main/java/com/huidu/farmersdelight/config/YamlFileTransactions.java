package com.huidu.farmersdelight.config;

import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes the complete read/modify/write cycle with a fixed-size lock table. */
public final class YamlFileTransactions {
    @FunctionalInterface
    public interface Operation<T> {
        T execute() throws Exception;
    }

    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    private YamlFileTransactions() {
    }

    public static <T> T execute(Path file, Operation<T> operation) throws Exception {
        // Case-insensitive identity matches the filesystem on Windows; real paths also unify symlinks.
        Path absolute = file.toAbsolutePath().normalize();
        Path existing = absolute;
        while (java.nio.file.Files.notExists(existing) && existing.getParent() != null) {
            existing = existing.getParent();
        }
        Path normalized = existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        String key = normalized.toString();
        if (java.io.File.separatorChar == '\\') {
            key = key.toLowerCase(java.util.Locale.ROOT);
        }
        ReentrantLock lock = LOCKS[Math.floorMod(key.hashCode(), LOCKS.length)];
        lock.lockInterruptibly();
        try {
            return operation.execute();
        } finally {
            lock.unlock();
        }
    }
}
