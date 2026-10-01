package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An isolated parse batch handed from a worker to the reload thread exactly once. */
public final class PreparedYamlFiles {
    private record Revision(long size, java.nio.file.attribute.FileTime modified, Object fileKey) {
        static Revision read(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new Revision(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        }
    }

    private final Map<Path, YamlConfiguration> documents;
    private final Map<Path, Revision> revisions;

    private PreparedYamlFiles(Map<Path, YamlConfiguration> documents, Map<Path, Revision> revisions) {
        this.documents = Map.copyOf(documents);
        this.revisions = Map.copyOf(revisions);
    }

    public static PreparedYamlFiles read(Path directory, List<String> files) throws Exception {
        Map<Path, YamlConfiguration> documents = new HashMap<>();
        Map<Path, Revision> revisions = new HashMap<>();
        for (String name : files) {
            Path file = directory.resolve(name);
            YamlFileTransactions.execute(file, () -> {
                Revision before = Revision.read(file);
                YamlConfiguration parsed = "gui.yml".equals(name)
                        ? PlainYamlDocuments.read(file) : ConfigFileUpdater.readYamlFile(file);
                if (!before.equals(Revision.read(file))) {
                    throw new IOException("Configuration changed during preparation: " + name);
                }
                documents.put(file, parsed);
                revisions.put(file, before);
                return null;
            });
        }
        return new PreparedYamlFiles(documents, revisions);
    }

    public void validateCurrent() throws IOException {
        for (var entry : revisions.entrySet()) {
            if (!entry.getValue().equals(Revision.read(entry.getKey()))) {
                throw new IOException("Configuration changed while reload was queued: " + entry.getKey().getFileName());
            }
        }
    }

    public Map<Path, YamlConfiguration> documents() {
        return documents;
    }

    public YamlConfiguration document(Path file) {
        return documents.get(file);
    }
}
