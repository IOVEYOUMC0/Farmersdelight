package com.huidu.farmersdelight.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FoodEffectConfigTest {

    @Test
    void returnsNullWhenFeatureIsDisabled() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                enabled: false
                foods:
                  farmersdelight:pasta_with_meatballs:
                    commands:
                      - "say {player}"
                """);

        FoodEffectConfig config = new FoodEffectConfig();
        config.loadFromConfig(yaml);

        assertNull(config.getFoodDefinition("farmersdelight:pasta_with_meatballs"));
    }

    @Test
    void parsesCustomEffectsAndCommandHooks() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                enabled: true
                foods:
                  farmersdelight:pasta_with_meatballs:
                    delay-ticks: 2
                    farmersdelight-effects:
                      comfort:
                        duration: 120
                      nourishment: 60
                    auraskills:
                      farming:
                        amount: 5.5
                        raw: true
                        chance: 25
                      custom_namespace/custom_skill: 2
                    commands:
                      - sender: player
                        requires-plugin: AuraSkills
                        command: "/skills xp add {player} farming 5"
                        chance: 50
                      - "say {player}"
                """);

        FoodEffectConfig config = new FoodEffectConfig();
        config.loadFromConfig(yaml);

        FoodEffectConfig.FoodEffectDefinition definition =
                config.getFoodDefinition("FarmersDelight:Pasta_With_Meatballs");
        assertNotNull(definition);
        assertEquals(2L, definition.delayTicks());
        assertEquals(2, definition.customEffects().size());
        assertEquals(FoodEffectConfig.CustomEffectType.COMFORT, definition.customEffects().getFirst().type());
        assertEquals(120, definition.customEffects().getFirst().durationSeconds());
        assertEquals(FoodEffectConfig.CustomEffectType.NOURISHMENT, definition.customEffects().get(1).type());
        assertEquals(60, definition.customEffects().get(1).durationSeconds());

        assertEquals(2, definition.auraSkillsXp().size());
        FoodEffectConfig.AuraSkillsXpDefinition farmingXp = definition.auraSkillsXp().getFirst();
        assertEquals("farming", farmingXp.skill());
        assertEquals(5.5D, farmingXp.amount(), 0.0001D);
        assertEquals(true, farmingXp.raw());
        assertEquals(0.25D, farmingXp.chance(), 0.0001D);
        assertEquals("custom_namespace/custom_skill", definition.auraSkillsXp().get(1).skill());
        assertEquals(2.0D, definition.auraSkillsXp().get(1).amount(), 0.0001D);

        assertEquals(2, definition.commands().size());
        FoodEffectConfig.CommandDefinition auraSkillsCommand = definition.commands().getFirst();
        assertEquals(FoodEffectConfig.CommandSenderType.PLAYER, auraSkillsCommand.sender());
        assertEquals("skills xp add {player} farming 5", auraSkillsCommand.command());
        assertEquals("AuraSkills", auraSkillsCommand.requiredPlugins().getFirst());
        assertEquals(0.5D, auraSkillsCommand.chance(), 0.0001D);
        assertEquals(FoodEffectConfig.CommandSenderType.CONSOLE, definition.commands().get(1).sender());
    }
}
