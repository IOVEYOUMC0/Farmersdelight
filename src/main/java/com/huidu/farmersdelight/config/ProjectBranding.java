package com.huidu.farmersdelight.config;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Updates displayed project names in existing documents while preserving persistent ids. */
public final class ProjectBranding {
    public static final String NAME = "Farmersdelight-Plugin-Pro";
    private static final Pattern LEGACY_NAME = Pattern.compile("\\bFarmersDelight\\b");
    private ProjectBranding() { }

    public static void migratePluginData(Path root) throws IOException {
        List<Path> documents = new ArrayList<>(List.of(root.resolve("config.yml"), root.resolve("gui.yml")));
        Path languages = root.resolve("lang");
        if (Files.isDirectory(languages)) {
            try (var files = Files.list(languages)) {
                documents.addAll(files.filter(path -> path.getFileName().toString().endsWith(".yml")).toList());
            }
        }
        for (Path path : documents) update(path, false);
    }

    public static int migrateCraftEnginePack(Path root) throws IOException {
        return update(root.resolve("pack.yml"), true)
                + update(root.resolve("configuration/translations.yml"), true);
    }

    private static int update(Path path, boolean pack) throws IOException {
        if (!Files.isRegularFile(path)) return 0;
        String original = Files.readString(path);
        String replacement = LEGACY_NAME.matcher(original).replaceAll(NAME);
        if (pack) {
            replacement = replacement.replace("author: HuiDu_OwO\n", "author: HuiDu_OwO, ydxc2009\n")
                    .replace("author: HuiDu_OwO\r\n", "author: HuiDu_OwO, ydxc2009\r\n")
                    .replace("description: Farmer's Delight", "description: " + NAME)
                    .replaceAll("(?m)^(\\s*farmersdelight\\.category\\.root:).*?$", "$1 \"" + NAME + "\"");
        }
        if (replacement.equals(original)) return 0;
        Path staged = Files.createTempFile(path.getParent(), ".branding-", ".tmp");
        try {
            Files.writeString(staged, replacement);
            try {
                Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        return 1;
    }
}
