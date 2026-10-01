package com.huidu.farmersdelight.util.compat;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KaleidoscopeCompatTest {
    @Test void recordedListsKeepDuplicatesAndNormalizeCraftEnginePrefixes() {
        assertEquals(List.of("minecraft:beef", "minecraft:beef", "farmersdelight:tomato"),
                KaleidoscopeCompat.parseIngredients("minecraft:beef, minecraft:beef,craftengine:farmersdelight:tomato"));
    }
    @Test void malformedAndOversizedListsAreRejectedAsAWhole() {
        for (String text : List.of("minecraft:beef,", ",minecraft:beef", "minecraft:beef,broken", "minecraft:air",
                "minecraft:beef,".repeat(10), "x".repeat(4097), "")) {
            assertTrue(KaleidoscopeCompat.parseIngredients(text).isEmpty(), text.substring(0, Math.min(30, text.length())));
        }
        assertTrue(KaleidoscopeCompat.parseIngredients(null).isEmpty());
        assertEquals(9, KaleidoscopeCompat.parseIngredients(String.join(",", java.util.Collections.nCopies(9, "minecraft:beef"))).size());
    }
}
