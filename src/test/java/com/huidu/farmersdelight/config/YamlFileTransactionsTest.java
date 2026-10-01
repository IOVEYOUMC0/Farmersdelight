package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class YamlFileTransactionsTest {
    @TempDir Path directory;

    @Test void concurrentReadModifyWriteDoesNotLoseEdits() throws Exception {
        Path file = directory.resolve("recipes.yml");
        Files.writeString(file, "recipes: {}\n");
        try (var pool = Executors.newFixedThreadPool(4)) {
            var work = new ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 48; i++) {
                int id = i;
                work.add(CompletableFuture.runAsync(() -> {
                    try {
                        YamlFileTransactions.execute(file, () -> {
                            var yaml = new YamlConfiguration();
                            yaml.loadFromString(Files.readString(file));
                            yaml.set("recipes.recipe" + id, id);
                            ConfigFileUpdater.writeStringAtomically(file, yaml.saveToString(), true);
                            return null;
                        });
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, pool));
            }
            CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).get();
        }
        var yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));
        assertEquals(48, yaml.getConfigurationSection("recipes").getKeys(false).size());
        for (int i = 0; i < 48; i++) assertEquals(i, yaml.getInt("recipes.recipe" + i));
    }

    @Test void parseFailureLeavesOriginalBytesAndReleasesLock() throws Exception {
        Path file = directory.resolve("broken.yml");
        String original = "recipes: [oops\n";
        Files.writeString(file, original);
        assertThrows(Exception.class, () -> YamlFileTransactions.execute(file, () -> {
            new YamlConfiguration().loadFromString(Files.readString(file));
            ConfigFileUpdater.writeStringAtomically(file, "lost: true\n", true);
            return null;
        }));
        assertEquals(original, Files.readString(file));
        assertEquals("released", YamlFileTransactions.execute(file, () -> "released"));
    }
}
