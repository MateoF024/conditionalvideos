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

    private FirstJoinConfig firstJoin = new FirstJoinConfig("", true, false);
    private Set<String> seenSessions = new HashSet<>();

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
            if (config.firstJoin == null) {
                config.firstJoin = new FirstJoinConfig("", true, false);
            }
            if (config.seenSessions == null) {
                config.seenSessions = new HashSet<>();
            }
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

    public FirstJoinConfig firstJoin() {
        return firstJoin;
    }

    public boolean hasSeenSession(String key) {
        return seenSessions.contains(key);
    }

    public void markSessionSeen(String key) {
        seenSessions.add(key);
    }

    public boolean shouldTrackSessionFor(FirstJoinConfig firstJoinConfig) {
        return firstJoinConfig != null && !firstJoinConfig.repeatableInSameSession();
    }

    private static Path configPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    public record FirstJoinConfig(String video, boolean skippable, boolean repeatableInSameSession) {
    }
}