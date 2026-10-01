package com.huidu.farmersdelight.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreparedYamlFilesTest {
    @TempDir Path directory;

    @Test void readsCompleteBatchWithoutMutatingSourceFiles() throws Exception {
        String config = "enabled: false\nunknown: keep\n";
        String gui = "recipes:\n  title: custom\n";
        Files.writeString(directory.resolve("config.yml"), config);
        Files.writeString(directory.resolve("gui.yml"), gui);
        var batch = PreparedYamlFiles.read(directory, List.of("config.yml", "gui.yml"));
        batch.validateCurrent();
        assertEquals(2, batch.documents().size());
        assertFalse(batch.document(directory.resolve("config.yml")).getBoolean("enabled"));
        assertEquals("custom", batch.document(directory.resolve("gui.yml")).getString("recipes.title"));
        assertEquals(config, Files.readString(directory.resolve("config.yml")));
        assertEquals(gui, Files.readString(directory.resolve("gui.yml")));
        assertThrows(UnsupportedOperationException.class, () -> batch.documents().clear());
    }

    @Test void editAfterPreparationRefusesStaleBatch() throws Exception {
        Path file = directory.resolve("gui.yml");
        Files.writeString(file, "title: before\n");
        var batch = PreparedYamlFiles.read(directory, List.of("gui.yml"));
        Files.writeString(file, "title: much-later-content\n");
        assertThrows(IOException.class, batch::validateCurrent);
        assertEquals("before", batch.document(file).getString("title"));
    }

    @Test void malformedAuxiliaryFileFailsWholePreparation() throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "enabled: true\n");
        Files.writeString(directory.resolve("gui.yml"), "broken: [oops\n");
        assertThrows(Exception.class, () -> PreparedYamlFiles.read(directory, List.of("config.yml", "gui.yml")));
        assertEquals("enabled: true\n", Files.readString(config));
    }
}
