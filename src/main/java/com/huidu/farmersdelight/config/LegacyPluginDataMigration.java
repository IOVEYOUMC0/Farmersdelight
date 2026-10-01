package com.huidu.farmersdelight.config;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Copies the previous plugin data into a staged directory before publishing the new brand's folder. */
public final class LegacyPluginDataMigration {
    private LegacyPluginDataMigration() { }

    public static boolean migrate(Path previous, Path destination) throws IOException {
        previous = previous.toAbsolutePath().normalize();
        destination = destination.toAbsolutePath().normalize();
        if (Files.exists(destination) || !Files.isDirectory(previous, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!previous.getParent().equals(destination.getParent()) || previous.equals(destination)) {
            throw new IOException("Plugin migration directories must be distinct siblings");
        }
        Path source = previous;
        Path stage = Files.createTempDirectory(destination.getParent(), ".plugin-data-migration-");
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(stage.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) throw new IOException("Linked plugin data cannot be migrated: " + file);
                    Files.copy(file, stage.resolve(source.relativize(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
            Files.move(stage, destination);
            return true;
        } finally {
            if (Files.exists(stage)) {
                Files.walkFileTree(stage, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                        if (error != null) throw error;
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }
}
