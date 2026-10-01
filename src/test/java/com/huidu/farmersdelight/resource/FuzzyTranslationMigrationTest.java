package com.huidu.farmersdelight.resource;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FuzzyTranslationMigrationTest {
    @Test void migrationAddsNewLabelsWithoutReplacingExistingCustomizationOrOtherKeys() {
        String migrated = ResourceInstaller.mergeFuzzyTranslations("{\"gui.fuzzy.mode_hint\":\"custom\",\"other\":\"original\"}",
                "{\"gui.fuzzy.mode_hint\":\"default\",\"gui.fuzzy.quality.superb\":\"完美\",\"unrelated\":\"ignored\"}");
        var json = JsonParser.parseString(migrated).getAsJsonObject();
        assertEquals("custom", json.get("gui.fuzzy.mode_hint").getAsString());
        assertEquals("original", json.get("other").getAsString());
        assertEquals("完美", json.get("gui.fuzzy.quality.superb").getAsString());
        assertFalse(json.has("unrelated"));
        assertNull(ResourceInstaller.mergeFuzzyTranslations(migrated, migrated));
    }
}
