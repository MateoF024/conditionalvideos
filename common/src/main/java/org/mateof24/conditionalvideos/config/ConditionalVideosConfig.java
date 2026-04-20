package org.mateof24.conditionalvideos.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;

import java.io.IOException;
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
    private static final String FIRST_JOIN_CONDITION_ID = "firstJoin";

    private ConditionConfig firstJoin = new ConditionConfig("", true, false, true, "#000000");
    private ConditionConfig playerDeath = new ConditionConfig("", true, true, true, "#000000");
    private Set<String> consumedConditionSessions = new HashSet<>();
    private Set<String> seenSessions = new HashSet<>(); // Legacy field kept for migration.


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
            config.migrateLegacySessionTracking();
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
            firstJoin = new ConditionConfig("", true, false, true, "#000000");
        }
        if (playerDeath == null) {
            playerDeath = new ConditionConfig("", true, true, true, "#000000");
        }
        if (consumedConditionSessions == null) {
            consumedConditionSessions = new HashSet<>();
        }
        if (seenSessions == null) {
            seenSessions = new HashSet<>();
        }
    }

    private void migrateLegacySessionTracking() {
        if (seenSessions.isEmpty()) {
            return;
        }

        for (String session : seenSessions) {
            consumedConditionSessions.add(conditionSessionKey(FIRST_JOIN_CONDITION_ID, session));
        }
        seenSessions.clear();
    }

    private static Path configPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    public record ConditionConfig(
            String video,
            boolean skippable,
            boolean repeatableInSameSession,
            boolean enableBackground,
            String colorBackground
    ) {
    }
}