package com.huidu.farmersdelight.config;

import net.momirealms.sparrow.yaml.SparrowYaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Sparrow parsing for plain internal documents; public Bukkit item serialization remains unchanged. */
public final class PlainYamlDocuments {
    private PlainYamlDocuments() {
    }

    public static YamlConfiguration read(Path path) throws IOException, InvalidConfigurationException {
        return parse(Files.readString(path));
    }

    public static YamlConfiguration parse(String contents) throws InvalidConfigurationException {
        try {
            var document = SparrowYaml.create().load(contents);
            var configuration = new YamlConfiguration();
            copy(document.getValues(), configuration);
            return configuration;
        } catch (IOException | RuntimeException invalidYaml) {
            throw new InvalidConfigurationException("Invalid plain YAML document", invalidYaml);
        }
    }

    private static void copy(Map<?, ?> values, ConfigurationSection section) {
        values.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (value instanceof Map<?, ?> children) {
                copy(children, section.createSection(name));
            } else {
                section.set(name, value);
            }
        });
    }
}
