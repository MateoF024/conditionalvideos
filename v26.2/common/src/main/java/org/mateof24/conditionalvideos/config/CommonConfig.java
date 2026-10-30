package org.mateof24.conditionalvideos.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.architectury.platform.Platform;
import org.mateof24.conditionalvideos.ConditionalVideos;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class CommonConfig {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String FILE_NAME = "conditionalvideos-common.json";

    public static final int CURRENT_COMMON_VERSION = 1;
    public static final String QUALITY_AUTO = "AUTO";
    public static final int DEFAULT_LOAD_TIMEOUT_SECONDS = 180;
    public static final int MIN_LOAD_TIMEOUT_SECONDS = 30;
    public static final float DEFAULT_VOLUME = 1.0f;

    private Integer configVersion;
    private String videoQuality;
    private Boolean alwaysShowCursor;
    private Boolean allowGameSounds;
    private Boolean blockMatureContent;
    private Boolean debugLogging;
    private Integer videoLoadTimeoutSeconds;
    private Float defaultVolume;

    public static CommonConfig load() {
        return load(configPath());
    }

    public static CommonConfig loadServer(Path gameDirectory) {
        return load(configPath(gameDirectory));
    }

    // Reads only the debugLogging flag, never creating or rewriting the file, so the debug log can be
    // switched on at startup without touching the user's config.
    public static boolean readDebugLogging(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                return false;
            }
            Boolean value = readBool(parsed.getAsJsonObject(), "debugLogging");
            return value != null && value;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    // A missing file is created with the defaults. An existing one only gets the options it lacks: what it
    // already holds, unknown options and questionable values included, stays exactly as written.
    public static CommonConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            CommonConfig config = new CommonConfig();
            config.ensureDefaults();
            write(file, GSON.toJsonTree(config));
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
        config.defaultVolume = readFloat(root, "defaultVolume");
        config.ensureDefaults();

        boolean added = false;
        for (Map.Entry<String, JsonElement> option : GSON.toJsonTree(config).getAsJsonObject().entrySet()) {
            if (!root.has(option.getKey())) {
                root.add(option.getKey(), option.getValue());
                added = true;
            }
        }
        if (added) {
            write(file, root);
        }
        return config;
    }

    // Only that option changes in the file; a file that cannot be read is left alone.
    public static void writeDefaultVolume(Path file, float volume) throws IOException {
        if (!Files.isRegularFile(file)) {
            load(file);
        }
        JsonElement parsed = JsonParser.parseString(ConditionFiles.readText(file));
        if (!parsed.isJsonObject()) {
            throw new IOException("'" + file.getFileName() + "' does not contain a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        root.addProperty("defaultVolume", volume);
        ConditionFiles.writeAtomically(file, root);
    }

    private static void write(Path file, JsonElement content) {
        try {
            ConditionFiles.writeAtomically(file, content);
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.error("Failed to save common config '{}'.", file, exception);
        }
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

    private static Float readFloat(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            try {
                return root.get(key).getAsFloat();
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private void ensureDefaults() {
        if (videoQuality == null || videoQuality.isBlank()) {
            videoQuality = QUALITY_AUTO;
        }
        if (alwaysShowCursor == null) {
            alwaysShowCursor = Boolean.FALSE;
        }
        if (allowGameSounds == null) {
            allowGameSounds = Boolean.FALSE;
        }
        if (blockMatureContent == null) {
            blockMatureContent = Boolean.TRUE;
        }
        if (debugLogging == null) {
            debugLogging = Boolean.FALSE;
        }
        if (videoLoadTimeoutSeconds == null) {
            videoLoadTimeoutSeconds = DEFAULT_LOAD_TIMEOUT_SECONDS;
        }
        if (defaultVolume == null) {
            defaultVolume = DEFAULT_VOLUME;
        }
        if (configVersion == null) {
            configVersion = CURRENT_COMMON_VERSION;
        }
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

    // Volume of a video without its own videoVolume. Clamped here, like the option above.
    public float defaultVolume() {
        if (defaultVolume == null || defaultVolume.isNaN()) {
            return DEFAULT_VOLUME;
        }
        return Math.max(0f, Math.min(1f, defaultVolume));
    }

    public boolean isAutoQuality() {
        return QUALITY_AUTO.equalsIgnoreCase(videoQuality());
    }

    // What the server dictates to its clients; blockMatureContent and debugLogging stay per installation.
    public String toAuthoritativeJson() {
        JsonObject root = new JsonObject();
        root.addProperty("videoQuality", videoQuality());
        root.addProperty("alwaysShowCursor", alwaysShowCursor());
        root.addProperty("allowGameSounds", allowGameSounds());
        root.addProperty("videoLoadTimeoutSeconds", videoLoadTimeoutSeconds());
        root.addProperty("defaultVolume", defaultVolume());
        return GSON.toJson(root);
    }

    // A server older than 1.6.0 sends neither the load timeout nor the default volume: those stay local.
    public static CommonConfig fromAuthoritativeJson(String rawJson, CommonConfig local) {
        CommonConfig config = new CommonConfig();
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                config.videoQuality = readString(root, "videoQuality");
                config.alwaysShowCursor = readBool(root, "alwaysShowCursor");
                config.allowGameSounds = readBool(root, "allowGameSounds");
                config.videoLoadTimeoutSeconds = readInt(root, "videoLoadTimeoutSeconds");
                config.defaultVolume = readFloat(root, "defaultVolume");
            }
        } catch (JsonSyntaxException exception) {
            ConditionalVideos.LOGGER.warn("Failed to parse synced common config JSON. Falling back to defaults.");
        }
        if (config.videoLoadTimeoutSeconds == null) {
            config.videoLoadTimeoutSeconds = local.videoLoadTimeoutSeconds;
        }
        if (config.defaultVolume == null) {
            config.defaultVolume = local.defaultVolume;
        }
        config.ensureDefaults();
        return config;
    }

    public static Path configPath() {
        return configPath(Platform.getGameFolder());
    }

    public static Path configPath(Path gameDirectory) {
        return gameDirectory.resolve("config").resolve(FILE_NAME);
    }
}
