package com.huidu.farmersdelight.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PlainYamlDocumentsTest {
    @Test void bundledGuiMatchesBukkitReader() throws Exception {
        String contents = Files.readString(Path.of("src/main/resources/gui.yml"));
        var bukkit = new YamlConfiguration();
        bukkit.loadFromString(contents);
        assertEquals(leaves(bukkit), leaves(PlainYamlDocuments.parse(contents)));
    }

    @Test void preservesFalseUnknownKeysListsAndLiteralNullWithoutWriting() throws Exception {
        String contents = "enabled: false\nunknown: custom\nlist: [null, value]\nopt-out: null\nempty: {}\n";
        var parsed = PlainYamlDocuments.parse(contents);
        assertFalse(parsed.getBoolean("enabled"));
        assertEquals("custom", parsed.getString("unknown"));
        assertEquals(2, parsed.getList("list").size());
        assertNull(parsed.getList("list").getFirst());
        assertFalse(parsed.isSet("opt-out"));
        assertTrue(parsed.isConfigurationSection("empty"));
    }

    @Test void malformedDocumentFailsExplicitly() {
        assertThrows(InvalidConfigurationException.class, () -> PlainYamlDocuments.parse("list: [oops\n"));
    }

    private static Map<String, Object> leaves(YamlConfiguration yaml) {
        var result = new TreeMap<String, Object>();
        yaml.getValues(true).forEach((path, value) -> {
            if (!(value instanceof org.bukkit.configuration.ConfigurationSection)) result.put(path, value);
        });
        return result;
    }
}
