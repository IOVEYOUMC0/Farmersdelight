package com.huidu.farmersdelight.api.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatapackSupportTest {

    @TempDir
    Path directory;

    @Test
    void findsNewDimensionWorldRootBeforeItsFirstSave() throws IOException {
        Path root = directory.resolve("world");
        Path overworld = Files.createDirectories(root.resolve("dimensions/minecraft/overworld"));
        Path custom = Files.createDirectories(root.resolve("dimensions/example/nested/test"));

        assertEquals(root, DatapackSupport.worldRoot(overworld));
        assertEquals(root, DatapackSupport.worldRoot(custom));
        assertFalse(Files.exists(root.resolve("level.dat")));
    }

    @Test
    void keepsLegacyWorldsIndependentAndFindsSavedDimensionRoots() throws IOException {
        Path root = Files.createDirectories(directory.resolve("world"));
        Files.createFile(root.resolve("level.dat"));
        Path nether = Files.createDirectories(root.resolve("DIM-1"));
        Path independent = Files.createDirectories(directory.resolve("world_nether"));
        Files.createFile(independent.resolve("level.dat"));

        assertEquals(root, DatapackSupport.worldRoot(nether));
        assertEquals(independent, DatapackSupport.worldRoot(independent));
    }

    @Test
    void keepsAnUnrecognizedUnsavedWorldFolder() throws IOException {
        Path root = Files.createDirectories(directory.resolve("fresh_world"));
        assertEquals(root, DatapackSupport.worldRoot(root));
    }

    @Test
    void recognizesSharedAndIndependentDatapackRoots() {
        Path primary = Path.of("world", "datapacks", "farmersdelight_enchant");

        assertTrue(DatapackSupport.sameNormalizedPath(primary,
                Path.of("world", "dimensions", "minecraft", "overworld", "..", "..", "..", "datapacks", "farmersdelight_enchant")));
        assertFalse(DatapackSupport.sameNormalizedPath(primary,
                Path.of("world_nether", "datapacks", "farmersdelight_enchant")));
    }
}
