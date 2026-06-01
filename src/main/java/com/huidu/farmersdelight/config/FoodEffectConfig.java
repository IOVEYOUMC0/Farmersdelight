package com.huidu.farmersdelight.config;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public class FoodEffectConfig {

    private static Logger LOGGER;

    private final Map<String, FoodEffectDefinition> foods = new HashMap<>();
    private boolean enabled;

    public static void setLogger(Logger logger) {
        LOGGER = logger;
    }

    public void loadFromConfig(ConfigurationSection section) {
        foods.clear();
        enabled = section != null && section.getBoolean("enabled", false);
        if (section == null) {
            return;
        }

        ConfigurationSection foodsSection = section.getConfigurationSection("foods");
        if (foodsSection == null) {
            return;
        }

        for (String foodId : foodsSection.getKeys(false)) {
            ConfigurationSection foodSection = foodsSection.getConfigurationSection(foodId);
            if (foodSection == null) {
                continue;
            }

            FoodEffectDefinition definition = parseFoodDefinition(foodSection);
            if (definition.hasActions()) {
                foods.put(normalizeFoodId(foodId), definition);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public FoodEffectDefinition getFoodDefinition(String foodId) {
        if (!enabled || foodId == null) {
            return null;
        }
        return foods.get(normalizeFoodId(foodId));
    }

    public Map<String, FoodEffectDefinition> getFoodDefinitions() {
        return Collections.unmodifiableMap(foods);
    }

    private FoodEffectDefinition parseFoodDefinition(ConfigurationSection section) {
        FoodEffectDefinition definition = new FoodEffectDefinition(Math.max(0L, section.getLong("delay-ticks", 1L)));
        loadPotionEffects(section, definition);
        loadFarmersDelightEffects(section, definition);
        loadCommands(section, definition);
        return definition;
    }

    private void loadPotionEffects(ConfigurationSection section, FoodEffectDefinition definition) {
        for (Map<?, ?> effectMap : getMapEntries(section, "effects")) {
            Object rawType = effectMap.get("type");
            if (rawType == null) {
                continue;
            }

            PotionEffectType type = resolveEffectType(String.valueOf(rawType));
            if (type == null) {
                continue;
            }

            int durationTicks = getDurationTicks(effectMap, 200);
            int amplifier = getInt(effectMap.get("amplifier"), 0);
            boolean ambient = getBoolean(firstPresent(effectMap, "ambient"), false);
            boolean particles = getBoolean(firstPresent(effectMap, "particles", "show-particles"), true);
            boolean icon = getBoolean(firstPresent(effectMap, "icon", "show-icon"), true);
            double chance = getChance(effectMap);
            definition.potionEffects.add(new PotionEffectDefinition(
                    type,
                    Math.max(1, durationTicks),
                    Math.max(0, amplifier),
                    ambient,
                    particles,
                    icon,
                    chance
            ));
        }
    }

    private void loadFarmersDelightEffects(ConfigurationSection section, FoodEffectDefinition definition) {
        for (Map<?, ?> effectMap : getMapEntries(section, "farmersdelight-effects")) {
            Object rawType = effectMap.get("type");
            if (rawType == null) {
                continue;
            }
            CustomEffectType type = CustomEffectType.from(String.valueOf(rawType));
            if (type == null) {
                logFine("Invalid FarmersDelight food effect: " + rawType);
                continue;
            }

            int durationSeconds = getDurationSeconds(effectMap, 300);
            definition.customEffects.add(new CustomEffectDefinition(type, Math.max(1, durationSeconds), getChance(effectMap)));
        }

        ConfigurationSection namedSection = section.getConfigurationSection("farmersdelight-effects");
        if (namedSection == null) {
            return;
        }

        for (String key : namedSection.getKeys(false)) {
            if (namedSection.isConfigurationSection(key)) {
                continue;
            }

            CustomEffectType type = CustomEffectType.from(key);
            if (type != null) {
                definition.customEffects.add(new CustomEffectDefinition(
                        type,
                        Math.max(1, namedSection.getInt(key, 300)),
                        1.0D
                ));
            }
        }
    }

    private void loadCommands(ConfigurationSection section, FoodEffectDefinition definition) {
        Object rawCommands = section.get("commands");
        if (rawCommands instanceof List<?> commandList) {
            for (Object entry : commandList) {
                CommandDefinition command = parseCommandEntry(entry);
                if (command != null) {
                    definition.commands.add(command);
                }
            }
            return;
        }

        ConfigurationSection commandsSection = section.getConfigurationSection("commands");
        if (commandsSection == null) {
            return;
        }

        for (String key : commandsSection.getKeys(false)) {
            ConfigurationSection commandSection = commandsSection.getConfigurationSection(key);
            if (commandSection != null) {
                CommandDefinition command = parseCommandMap(commandSection.getValues(false));
                if (command != null) {
                    definition.commands.add(command);
                }
                continue;
            }

            String commandText = commandsSection.getString(key);
            if (commandText != null) {
                CommandDefinition command = parseCommandString(commandText);
                if (command != null) {
                    definition.commands.add(command);
                }
            }
        }
    }

    private CommandDefinition parseCommandEntry(Object entry) {
        if (entry instanceof String commandText) {
            return parseCommandString(commandText);
        }
        if (entry instanceof Map<?, ?> commandMap) {
            return parseCommandMap(commandMap);
        }
        return null;
    }

    private CommandDefinition parseCommandString(String commandText) {
        String command = normalizeCommand(commandText);
        if (command.isEmpty()) {
            return null;
        }
        return new CommandDefinition(CommandSenderType.CONSOLE, command, List.of(), 1.0D);
    }

    private CommandDefinition parseCommandMap(Map<?, ?> commandMap) {
        Object rawCommand = firstPresent(commandMap, "command", "value");
        if (rawCommand == null) {
            return null;
        }

        String command = normalizeCommand(String.valueOf(rawCommand));
        if (command.isEmpty()) {
            return null;
        }

        CommandSenderType sender = CommandSenderType.from(String.valueOf(firstPresent(commandMap, "sender", "run-as")));
        List<String> requiredPlugins = getStringList(firstPresent(commandMap,
                "requires-plugin",
                "required-plugin",
                "plugin",
                "requires-plugins",
                "required-plugins"));
        return new CommandDefinition(sender, command, requiredPlugins, getChance(commandMap));
    }

    private List<Map<?, ?>> getMapEntries(ConfigurationSection section, String path) {
        List<Map<?, ?>> entries = new ArrayList<>();
        Object raw = section.get(path);
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> mapEntry) {
                    entries.add(mapEntry);
                }
            }
            return entries;
        }

        ConfigurationSection childSection = section.getConfigurationSection(path);
        if (childSection == null) {
            return entries;
        }

        for (String key : childSection.getKeys(false)) {
            ConfigurationSection entrySection = childSection.getConfigurationSection(key);
            if (entrySection != null) {
                Map<String, Object> values = new HashMap<>(entrySection.getValues(false));
                values.putIfAbsent("type", key);
                entries.add(values);
            }
        }
        return entries;
    }

    private PotionEffectType resolveEffectType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }

        String normalized = typeName.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        PotionEffectType effectType = resolveEffectKey(normalized);
        if (effectType != null) {
            return effectType;
        }

        effectType = resolveEffectKey(normalized.replace('_', '.'));
        if (effectType != null) {
            return effectType;
        }

        logFine("Invalid potion effect: " + typeName);
        return null;
    }

    private PotionEffectType resolveEffectKey(String keyText) {
        NamespacedKey key = keyText.contains(":")
                ? NamespacedKey.fromString(keyText)
                : NamespacedKey.minecraft(keyText);
        if (key == null) {
            return null;
        }
        return Registry.EFFECT.get(key);
    }

    private int getDurationTicks(Map<?, ?> map, int defaultValue) {
        Object secondsValue = firstPresent(map, "duration-seconds", "seconds");
        if (secondsValue != null) {
            return Math.max(1, getInt(secondsValue, Math.max(1, defaultValue / 20)) * 20);
        }
        return getInt(firstPresent(map, "duration-ticks", "duration"), defaultValue);
    }

    private int getDurationSeconds(Map<?, ?> map, int defaultValue) {
        Object ticksValue = firstPresent(map, "duration-ticks", "ticks");
        if (ticksValue != null) {
            return Math.max(1, (int) Math.ceil(getInt(ticksValue, defaultValue * 20) / 20.0D));
        }
        return getInt(firstPresent(map, "duration-seconds", "seconds", "duration"), defaultValue);
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private int getInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private boolean getBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return defaultValue;
    }

    private double getChance(Map<?, ?> map) {
        return normalizeChance(getDouble(firstPresent(map, "chance", "probability"), 1.0D));
    }

    private double normalizeChance(double chance) {
        if (chance > 1.0D) {
            chance /= 100.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, chance));
    }

    private double getDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private List<String> getStringList(Object value) {
        if (value instanceof List<?> rawList) {
            return rawList.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(text -> !text.isEmpty())
                    .toList();
        }
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? List.of() : List.of(text);
    }

    private String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        String normalized = command.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private String normalizeFoodId(String foodId) {
        return foodId.trim().toLowerCase(Locale.ROOT);
    }

    private void logFine(String message) {
        if (LOGGER != null) {
            LOGGER.fine(message);
        }
    }

    public static final class FoodEffectDefinition {
        private final List<PotionEffectDefinition> potionEffects = new ArrayList<>();
        private final List<CustomEffectDefinition> customEffects = new ArrayList<>();
        private final List<CommandDefinition> commands = new ArrayList<>();
        private final long delayTicks;

        private FoodEffectDefinition(long delayTicks) {
            this.delayTicks = delayTicks;
        }

        public List<PotionEffectDefinition> potionEffects() {
            return potionEffects;
        }

        public List<CustomEffectDefinition> customEffects() {
            return customEffects;
        }

        public List<CommandDefinition> commands() {
            return commands;
        }

        public long delayTicks() {
            return delayTicks;
        }

        private boolean hasActions() {
            return !potionEffects.isEmpty() || !customEffects.isEmpty() || !commands.isEmpty();
        }
    }

    public record PotionEffectDefinition(PotionEffectType type, int durationTicks, int amplifier, boolean ambient,
                                         boolean particles, boolean icon, double chance) {
    }

    public record CustomEffectDefinition(CustomEffectType type, int durationSeconds, double chance) {
    }

    public record CommandDefinition(CommandSenderType sender, String command, List<String> requiredPlugins,
                                    double chance) {
    }

    public enum CustomEffectType {
        COMFORT,
        NOURISHMENT;

        private static CustomEffectType from(String rawType) {
            if (rawType == null) {
                return null;
            }
            String normalized = rawType.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            return switch (normalized) {
                case "comfort" -> COMFORT;
                case "nourishment", "nourishing" -> NOURISHMENT;
                default -> null;
            };
        }
    }

    public enum CommandSenderType {
        CONSOLE,
        PLAYER;

        private static CommandSenderType from(String rawType) {
            if (rawType == null) {
                return CONSOLE;
            }
            String normalized = rawType.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            return switch (normalized) {
                case "player", "user" -> PLAYER;
                default -> CONSOLE;
            };
        }
    }
}
