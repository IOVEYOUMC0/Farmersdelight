package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.YamlFileTransactions;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerialization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Worker-owned plain documents, converted to Bukkit objects only inside the publication scope. */
public final class PreparedRecipeFiles implements RecipeReloadCoordinator.Batch {
    public static final List<String> FILES = List.of("recipes/cooking_pot_recipes.yml", "recipes/cutting_board_recipes.yml");
    private static final ThreadLocal<PreparedRecipeFiles> CURRENT = new ThreadLocal<>();
    private record Revision(long size, FileTime modified, Object key) {
        static Revision read(Path path) throws IOException {
            BasicFileAttributes value = Files.readAttributes(path, BasicFileAttributes.class);
            return new Revision(value.size(), value.lastModifiedTime(), value.fileKey());
        }
    }

    private final Map<Path, YamlConfiguration> plain;
    private final Map<Path, Revision> revisions;
    private final Map<Path, YamlConfiguration> materialized = new LinkedHashMap<>();

    private PreparedRecipeFiles(Map<Path, YamlConfiguration> plain, Map<Path, Revision> revisions) {
        this.plain = Map.copyOf(plain);
        this.revisions = Map.copyOf(revisions);
    }

    public static PreparedRecipeFiles read(FarmersDelightPlugin plugin, boolean mergeMissing) throws Exception {
        Map<Path, YamlConfiguration> documents = new LinkedHashMap<>();
        Map<Path, Revision> revisions = new LinkedHashMap<>();
        for (String name : FILES) {
            Path path = plugin.getDataFolder().toPath().resolve(name).toAbsolutePath().normalize();
            YamlFileTransactions.execute(path, () -> {
                YamlConfiguration document = RecipeFileLoader.prepareRecipeFile(plugin, name, mergeMissing);
                if (document == null) throw new IOException("Recipe file could not be prepared: " + name);
                documents.put(path, document);
                revisions.put(path, Revision.read(path));
                return null;
            });
        }
        return new PreparedRecipeFiles(documents, revisions);
    }

    public void validateCurrent() throws IOException {
        for (var entry : revisions.entrySet()) {
            if (!entry.getValue().equals(Revision.read(entry.getKey()))) {
                throw new IOException("Recipe file changed during preparation: " + entry.getKey().getFileName());
            }
        }
    }

    public void publishWithin(Runnable publication) {
        PreparedRecipeFiles previous = CURRENT.get();
        CURRENT.set(this);
        try {
            // A bad legacy serialized value must fail before either manager changes its live snapshot.
            for (Path path : plain.keySet()) currentDocument(path);
            publication.run();
        }
        finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    static YamlConfiguration currentDocument(Path path) {
        PreparedRecipeFiles batch = CURRENT.get();
        if (batch == null) return null;
        Path key = path.toAbsolutePath().normalize();
        YamlConfiguration plain = batch.plain.get(key);
        if (plain == null) return null;
        return batch.materialized.computeIfAbsent(key, ignored -> materialize(plain));
    }

    static YamlConfiguration materialize(YamlConfiguration plain) {
        YamlConfiguration configuration = new YamlConfiguration();
        copy(plain.getValues(false), configuration);
        return configuration;
    }

    private static void copy(Map<?, ?> values, ConfigurationSection destination) {
        values.forEach((key, value) -> {
            String name = String.valueOf(key);
            Object converted = convert(value);
            if (converted instanceof Map<?, ?> children) copy(children, destination.createSection(name));
            else destination.set(name, converted);
        });
    }

    private static Object convert(Object value) {
        if (value instanceof ConfigurationSection section) return convert(section.getValues(false));
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, child) -> copy.put(String.valueOf(key), convert(child)));
            if (copy.containsKey(ConfigurationSerialization.SERIALIZED_TYPE_KEY)) {
                Object decoded = ConfigurationSerialization.deserializeObject(copy);
                if (decoded == null) throw new IllegalArgumentException("Invalid serialized recipe value: " + copy.get(ConfigurationSerialization.SERIALIZED_TYPE_KEY));
                return decoded;
            }
            return copy;
        }
        if (value instanceof List<?> source) {
            List<Object> copy = new ArrayList<>(source.size());
            for (Object child : source) copy.add(convert(child));
            return copy;
        }
        return value;
    }
}
