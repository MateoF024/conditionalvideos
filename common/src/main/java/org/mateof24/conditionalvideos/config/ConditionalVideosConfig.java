package org.mateof24.conditionalvideos.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class ConditionalVideosConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "conditionalvideos.json";

    private ConditionConfig firstJoin = new ConditionConfig("", true, false, true, "#000000", "", "bottomLeft", "", "bottomLeft");
    private ConditionConfig playerDeath = new ConditionConfig("", true, true, true, "#000000", "", "bottomLeft", "", "bottomLeft");
    private Map<String, ConditionConfig> entityKilled = new HashMap<>();
    private Map<String, ConditionConfig> deathByEntity = new HashMap<>();
    private Map<String, ConditionConfig> advancementCompleted = new HashMap<>();
    private Map<String, ConditionConfig> dimensionChanged = new HashMap<>();
    private Set<String> consumedConditionSessions = new HashSet<>();

    public static ConditionalVideosConfig load() {
        Path file = configPath();
        if (!Files.isRegularFile(file)) {
            ConditionalVideosConfig config = new ConditionalVideosConfig();
            config.save();
            return config;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ConditionalVideosConfig config = GSON.fromJson(reader, ConditionalVideosConfig.class);
            if (config == null) {
                config = new ConditionalVideosConfig();
            }
            config.ensureDefaults();
            return config;
        } catch (IOException | JsonSyntaxException exception) {
            ConditionalVideos.LOGGER.error("Failed to read config file '{}'. Recreating with defaults.", file, exception);
            ConditionalVideosConfig fallback = new ConditionalVideosConfig();
            fallback.save();
            return fallback;
        }
    }

    public void save() {
        Path file = configPath();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.error("Failed to save config file '{}'.", file, exception);
        }
    }

    public ConditionConfig firstJoin() {
        return firstJoin;
    }

    public ConditionConfig playerDeath() {
        return playerDeath;
    }

    public Map<String, ConditionConfig> deathByEntity() {
        return deathByEntity;
    }

    public Map<String, ConditionConfig> entityKilled() {
        return entityKilled;
    }

    public Map<String, ConditionConfig> advancementCompleted() {
        return advancementCompleted;
    }

    public Map<String, ConditionConfig> dimensionChanged() {
        return dimensionChanged;
    }

    public boolean hasConsumedConditionSession(String conditionId, String sessionKey) {
        return consumedConditionSessions.contains(conditionSessionKey(conditionId, sessionKey));
    }

    public void markConditionSessionConsumed(String conditionId, String sessionKey) {
        consumedConditionSessions.add(conditionSessionKey(conditionId, sessionKey));
    }

    public int resolveBackgroundColor(ConditionConfig conditionConfig, int fallbackColor) {
        if (conditionConfig == null || !conditionConfig.enableBackground()) {
            return fallbackColor;
        }

        String hexColor = conditionConfig.colorBackground();
        if (hexColor == null || hexColor.isBlank()) {
            return fallbackColor;
        }

        String cleanedHex = hexColor.trim();
        if (cleanedHex.startsWith("#")) {
            cleanedHex = cleanedHex.substring(1);
        }

        try {
            if (cleanedHex.length() == 6) {
                return (int) (0xFF000000L | Long.parseLong(cleanedHex, 16));
            }

            if (cleanedHex.length() == 8) {
                return (int) Long.parseLong(cleanedHex, 16);
            }
        } catch (NumberFormatException exception) {
            ConditionalVideos.LOGGER.warn("Invalid colorBackground '{}' in config. Using fallback color.", hexColor);
        }

        return fallbackColor;
    }

    private static String conditionSessionKey(String conditionId, String sessionKey) {
        return conditionId + ":" + sessionKey;
    }

    private void ensureDefaults() {
        if (firstJoin == null) {
            firstJoin = new ConditionConfig("", true, false, true, "#000000", "", "bottomLeft", "", "bottomLeft");
        }
        if (playerDeath == null) {
            playerDeath = new ConditionConfig("", true, true, true, "#000000", "", "bottomLeft", "", "bottomLeft");
        }
        if (deathByEntity == null) {
            deathByEntity = new HashMap<>();
        }
        if (entityKilled == null) {
            entityKilled = new HashMap<>();
        }
        if (advancementCompleted == null) {
            advancementCompleted = new HashMap<>();
        }
        if (dimensionChanged == null) {
            dimensionChanged = new HashMap<>();
        }
        if (consumedConditionSessions == null) {
            consumedConditionSessions = new HashSet<>();
        }
    }

    private static Path configPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    public record ConditionConfig(
            String video,
            boolean skippable,
            boolean repeatableInSameSession,
            boolean enableBackground,
            String colorBackground,
            String videoTitle,
            String videoTitlePosition,
            String videoDescription,
            String videoDescriptionPosition
    ) {
    }
}