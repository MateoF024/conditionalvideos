package org.mateof24.conditionalvideos.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.debug.DebugLog;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CommonConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "conditionalvideos-common.json";

    public static final int CURRENT_COMMON_VERSION = 1;
    public static final String QUALITY_AUTO = "AUTO";
    public static final int DEFAULT_LOAD_TIMEOUT_SECONDS = 180;
    public static final int MIN_LOAD_TIMEOUT_SECONDS = 30;

    private Integer configVersion;
    private String videoQuality;
    private Boolean alwaysShowCursor;
    private Boolean allowGameSounds;
    private Boolean blockMatureContent;
    private Boolean debugLogging;
    private Integer videoLoadTimeoutSeconds;

    public static CommonConfig load() {
        CommonConfig config = load(configPath());
        DebugLog.setEnabled(config.debugLogging());
        return config;
    }

    public static CommonConfig loadServer(Path gameDirectory) {
        CommonConfig config = load(gameDirectory.resolve("config").resolve(FILE_NAME));
        DebugLog.setEnabled(config.debugLogging());
        return config;
    }

    public static CommonConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            CommonConfig config = new CommonConfig();
            config.ensureDefaults();
            config.save(file);
            return config;
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                ConditionalVideos.LOGGER.error("Common config '{}' is not a JSON object. Using defaults this session; the file is left UNTOUCHED so you can fix it.", file);
                CommonConfig fallback = new CommonConfig();
                fallback.ensureDefaults();
                return fallback;
            }
            root = parsed.getAsJsonObject();
        } catch (IOException | JsonSyntaxException exception) {
            ConditionalVideos.LOGGER.error("Failed to read common config '{}' (likely a JSON syntax error). Using defaults this session; the file is left UNTOUCHED so you can fix it: {}", file, exception.toString());
            CommonConfig fallback = new CommonConfig();
            fallback.ensureDefaults();
            return fallback;
        }

        CommonConfig config = new CommonConfig();
        config.configVersion = readInt(root, "configVersion");
        config.videoQuality = readString(root, "videoQuality");
        config.alwaysShowCursor = readBool(root, "alwaysShowCursor");
        config.allowGameSounds = readBool(root, "allowGameSounds");
        config.blockMatureContent = readBool(root, "blockMatureContent");
        config.debugLogging = readBool(root, "debugLogging");
        config.videoLoadTimeoutSeconds = readInt(root, "videoLoadTimeoutSeconds");
        boolean changed = config.ensureDefaults();
        if (changed) {
            config.save(file);
        }
        return config;
    }

    private static Boolean readBool(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            try {
                return root.get(key).getAsBoolean();
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private static String readString(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            try {
                return root.get(key).getAsString();
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Integer readInt(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            try {
                return root.get(key).getAsInt();
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    public void save() {
        save(configPath());
    }

    public void saveServer(Path gameDirectory) {
        save(gameDirectory.resolve("config").resolve(FILE_NAME));
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.error("Failed to save common config '{}'.", file, exception);
        }
    }

    private boolean ensureDefaults() {
        boolean changed = false;
        if (videoQuality == null || videoQuality.isBlank()) {
            videoQuality = QUALITY_AUTO;
            changed = true;
        }
        if (alwaysShowCursor == null) {
            alwaysShowCursor = Boolean.FALSE;
            changed = true;
        }
        if (allowGameSounds == null) {
            allowGameSounds = Boolean.FALSE;
            changed = true;
        }
        if (blockMatureContent == null) {
            blockMatureContent = Boolean.TRUE;
            changed = true;
        }
        if (debugLogging == null) {
            debugLogging = Boolean.FALSE;
            changed = true;
        }
        if (videoLoadTimeoutSeconds == null) {
            videoLoadTimeoutSeconds = DEFAULT_LOAD_TIMEOUT_SECONDS;
            changed = true;
        }
        if (configVersion == null || configVersion < CURRENT_COMMON_VERSION) {
            configVersion = CURRENT_COMMON_VERSION;
            changed = true;
        }
        return changed;
    }

    public String videoQuality() {
        return videoQuality == null || videoQuality.isBlank() ? QUALITY_AUTO : videoQuality.trim();
    }

    public boolean alwaysShowCursor() {
        return alwaysShowCursor != null && alwaysShowCursor;
    }

    public boolean allowGameSounds() {
        return allowGameSounds != null && allowGameSounds;
    }

    public boolean blockMatureContent() {
        return blockMatureContent == null || blockMatureContent;
    }

    public boolean debugLogging() {
        return debugLogging != null && debugLogging;
    }

    // Seconds to wait for a source to start (MRL resolve + first frame) before giving up. A bad/low
    // value is clamped here rather than rewritten, so the file is never reset on a questionable entry.
    public int videoLoadTimeoutSeconds() {
        if (videoLoadTimeoutSeconds == null) {
            return DEFAULT_LOAD_TIMEOUT_SECONDS;
        }
        return Math.max(MIN_LOAD_TIMEOUT_SECONDS, videoLoadTimeoutSeconds);
    }

    public boolean isAutoQuality() {
        return QUALITY_AUTO.equalsIgnoreCase(videoQuality());
    }

    public String toAuthoritativeJson() {
        JsonObject root = new JsonObject();
        root.addProperty("videoQuality", videoQuality());
        root.addProperty("alwaysShowCursor", alwaysShowCursor());
        root.addProperty("allowGameSounds", allowGameSounds());
        return GSON.toJson(root);
    }

    public static CommonConfig fromAuthoritativeJson(String rawJson) {
        CommonConfig config = new CommonConfig();
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                if (root.has("videoQuality") && root.get("videoQuality").isJsonPrimitive()) {
                    config.videoQuality = root.get("videoQuality").getAsString();
                }
                if (root.has("alwaysShowCursor") && root.get("alwaysShowCursor").isJsonPrimitive()) {
                    config.alwaysShowCursor = root.get("alwaysShowCursor").getAsBoolean();
                }
                if (root.has("allowGameSounds") && root.get("allowGameSounds").isJsonPrimitive()) {
                    config.allowGameSounds = root.get("allowGameSounds").getAsBoolean();
                }
            }
        } catch (JsonSyntaxException exception) {
            ConditionalVideos.LOGGER.warn("Failed to parse synced common config JSON. Falling back to defaults.");
        }
        config.ensureDefaults();
        return config;
    }

    private static Path configPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }
}
