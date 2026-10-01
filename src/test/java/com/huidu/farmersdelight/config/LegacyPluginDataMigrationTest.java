package com.huidu.farmersdelight.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LegacyPluginDataMigrationTest {
    @TempDir Path plugins;

    @Test void preservesConfigurationRecipesAndDiscoveryAndKeepsOriginal() throws Exception {
        Path previous = Files.createDirectory(plugins.resolve("FarmersDelight"));
        Files.writeString(previous.resolve("config.yml"), "config-version: 2\ncustom: retained\n");
        Path nested = Files.createDirectories(previous.resolve("recipes/cooking-pot"));
        Files.writeString(nested.resolve("custom.yml"), "ingredients: [minecraft:potato]\n");
        Files.write(previous.resolve("discovery.yml"), new byte[]{1, 2, 3});
        Path destination = plugins.resolve("Farmersdelight-Plugin-Pro");
        assertTrue(LegacyPluginDataMigration.migrate(previous, destination));
        assertEquals(Files.readString(previous.resolve("config.yml")), Files.readString(destination.resolve("config.yml")));
        assertEquals(Files.readString(nested.resolve("custom.yml")), Files.readString(destination.resolve("recipes/cooking-pot/custom.yml")));
        assertArrayEquals(Files.readAllBytes(previous.resolve("discovery.yml")), Files.readAllBytes(destination.resolve("discovery.yml")));
        assertFalse(LegacyPluginDataMigration.migrate(previous, destination));
    }

    @Test void neverOverwritesAnExistingNewInstallation() throws Exception {
        Path previous = Files.createDirectory(plugins.resolve("old"));
        Path destination = Files.createDirectory(plugins.resolve("new"));
        Files.writeString(previous.resolve("config.yml"), "old");
        Files.writeString(destination.resolve("config.yml"), "new");
        assertFalse(LegacyPluginDataMigration.migrate(previous, destination));
        assertEquals("new", Files.readString(destination.resolve("config.yml")));
    }

    @Test void freshInstallationNeedsNoMigration() throws Exception {
        Path destination = plugins.resolve("new");
        assertFalse(LegacyPluginDataMigration.migrate(plugins.resolve("missing"), destination));
        assertFalse(Files.exists(destination));
    }

    @Test void rejectsOverlappingOrUnrelatedFoldersBeforeCopying() throws Exception {
        Path previous = Files.createDirectory(plugins.resolve("old"));
        assertThrows(java.io.IOException.class, () -> LegacyPluginDataMigration.migrate(previous, previous.resolve("nested")));
        assertFalse(Files.exists(previous.resolve("nested")));
    }
}
