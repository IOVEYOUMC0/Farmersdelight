package com.huidu.farmersdelight.config;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PotEditorMigrationTest {
    @Test void onlyThePreviousBundledLayoutGetsTheNewEditorControls() throws Exception {
        var bundled = new YamlConfiguration(); bundled.loadFromString(Files.readString(Path.of("src/main/resources/gui.yml")));
        var old = new YamlConfiguration();
        old.set("recipe-editor-gui.layout", List.of("####i####", "#III#C#R#", "#III###N#", "#########", "#T#E#P#G#", "X#O#D#K#S"));
        assertEquals(1, ConfigBootstrap.migrateDefaultPotEditor(old, bundled));
        assertEquals("#M#Q#H#F#", old.getStringList("recipe-editor-gui.layout").get(3));
        assertEquals(0, ConfigBootstrap.migrateDefaultPotEditor(old, bundled));
        old.set("recipe-editor-gui.layout", List.of("CUSTOMGUI"));
        assertEquals(0, ConfigBootstrap.migrateDefaultPotEditor(old, bundled));
        assertEquals(List.of("CUSTOMGUI"), old.getStringList("recipe-editor-gui.layout"));
    }
}
