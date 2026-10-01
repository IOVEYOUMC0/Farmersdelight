package com.huidu.farmersdelight.recipe;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FoodGroupStoreTest {
    @Test void localGroupsNormalizeMembersAndPreserveOrderForOverlappingGroups() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("groups:\n  fd:red_meat:\n    kind: equivalent\n    items: [craftengine:fd:beef, minecraft:porkchop, fd:beef]\n  fd:seasonings:\n    kind: seasoning\n    items: [minecraft:sugar]\n");
        var groups = FoodGroupStore.read(yaml);
        assertEquals(2, groups.getFirst().items().size());
        var snapshot = FoodGroupSnapshot.of(groups);
        assertEquals("#fd:red_meat", snapshot.canonical("fd:beef"));
        assertTrue(snapshot.seasonings().contains("minecraft:sugar"));
    }
    @Test void invalidGroupsCannotPublishEmptyOrUnsupportedRules() throws Exception {
        for (String value : new String[]{"groups: {fd:x: {kind: typo, items: [minecraft:beef]}}", "groups: {fd:x: {items: []}}",
                "groups: {fd:x: {items: ['#fd:nested']}}"}) {
            var yaml = new YamlConfiguration(); yaml.loadFromString(value);
            assertThrows(IllegalArgumentException.class, () -> FoodGroupStore.read(yaml));
        }
    }
}
