package org.mateof24.conditionalvideos.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConditionalVideosConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CLIENT_FILE_NAME = "conditionalvideos.json";
    private static final String SERVER_FILE_NAME = "conditionalvideos-server.json";

    public static final int CURRENT_CONFIG_VERSION = 3;
    static final float DEFAULT_TEXT_SCALE = 1.0f;
    static final float DEFAULT_VIDEO_VOLUME = 1.0f;
    static final float TEXT_BOX_OPACITY_LEGACY = -1.0f;
    public static final String TRANSITION_CUT = "cut";
    public static final String TRANSITION_FADE = "fadeOut/In";

    public static final String COMPARATOR_EQUAL = "equal";
    public static final String COMPARATOR_LESS = "less";
    public static final String COMPARATOR_GREATER = "greater";
    public static final String COMPARATOR_LESS_OR_EQUAL = "lessOrEqual";
    public static final String COMPARATOR_GREATER_OR_EQUAL = "greaterOrEqual";
    private static final String[] VALID_COMPARATORS = {
            COMPARATOR_EQUAL, COMPARATOR_LESS, COMPARATOR_GREATER,
            COMPARATOR_LESS_OR_EQUAL, COMPARATOR_GREATER_OR_EQUAL
    };

    private static final String[] LEGACY_DECORATOR_FIELDS = {
            "skippable", "videoLoop", "enableBackground", "colorBackground",
            "videoTitle", "videoTitlePosition",
            "videoDescription", "videoDescriptionPosition",
            "titleTextScale", "descriptionTextScale", "textBoxOpacity",
            "videoVolume", "transition", "nextAt"
    };

    private Integer configVersion;
    private ConditionConfig firstJoin = defaultFirstJoin();
    private ConditionConfig playerDeath = defaultPlayerDeath();
    private Map<String, ConditionConfig> entityKilled = new HashMap<>();
    private Map<String, ConditionConfig> deathByEntity = new HashMap<>();
    private Map<String, ConditionConfig> advancementCompleted = new HashMap<>();
    private Map<String, ConditionConfig> dimensionChanged = new HashMap<>();
    private ConditionConfig totemUsed = defaultSimpleCondition();
    private ConditionConfig bedSleep = defaultSimpleCondition();
    private Map<String, ConditionConfig> itemObtained = new HashMap<>();
    private Map<String, ConditionConfig> itemCrafted = new HashMap<>();
    private Map<String, ConditionConfig> recipeUnlocked = new HashMap<>();
    private Map<String, ScoreboardConditionConfig> scoreboard = new HashMap<>();
    private Map<String, ConditionConfig> custom = new HashMap<>();
    private Set<String> consumedConditionSessions = new HashSet<>();

    public static ConditionalVideosConfig load() {
        return load(configPath(CLIENT_FILE_NAME));
    }

    public static ConditionalVideosConfig loadServer(Path gameDirectory) {
        return load(gameDirectory.resolve("config").resolve(SERVER_FILE_NAME));
    }

    public static Path clientConfigPath(Path gameDirectory) {
        return gameDirectory.resolve("config").resolve(CLIENT_FILE_NAME);
    }

    public static Path serverConfigPath(Path gameDirectory) {
        return gameDirectory.resolve("config").resolve(SERVER_FILE_NAME);
    }

    private static final Map<String, String> LAST_CONFIG_ISSUE = new HashMap<>();

    private static synchronized boolean shouldLogConfigIssue(String fileKey, String signature) {
        String previous = LAST_CONFIG_ISSUE.get(fileKey);
        if (signature.equals(previous)) {
            return false;
        }
        LAST_CONFIG_ISSUE.put(fileKey, signature);
        return true;
    }

    private static synchronized void clearConfigIssueLog(String fileKey) {
        LAST_CONFIG_ISSUE.remove(fileKey);
    }

    public static ConditionalVideosConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            ConditionalVideosConfig config = new ConditionalVideosConfig();
            config.ensureDefaults();
            config.save(file);
            return config;
        }

        String fileKey = file.toString();
        JsonObject rootJson;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                if (shouldLogConfigIssue(fileKey, "not-json-object")) {
                    ConditionalVideos.LOGGER.error("Config '{}' is not a JSON object. Using defaults for this session; the file is left UNTOUCHED so you can fix it without losing your work.", file);
                }
                ConditionalVideosConfig fallback = new ConditionalVideosConfig();
                fallback.ensureDefaults();
                return fallback;
            }
            rootJson = parsed.getAsJsonObject();
        } catch (IOException | JsonSyntaxException exception) {
            if (shouldLogConfigIssue(fileKey, "syntax:" + exception)) {
                ConditionalVideos.LOGGER.error("Failed to read config '{}' (likely a JSON syntax error such as a missing comma or brace). Using defaults for this session; the file is left UNTOUCHED so you can fix it without losing your work: {}", file, exception.toString());
            }
            ConditionalVideosConfig fallback = new ConditionalVideosConfig();
            fallback.ensureDefaults();
            return fallback;
        }

        int loadedVersion = readConfigVersion(rootJson);
        try {
            boolean needsMigration = loadedVersion < CURRENT_CONFIG_VERSION || hasLegacySchema(rootJson);
            if (needsMigration) {
                backupBeforeMigrate(file, loadedVersion);
                migrateLegacySchema(rootJson);
                rootJson.addProperty("configVersion", CURRENT_CONFIG_VERSION);
            }

            List<String> skipped = new ArrayList<>();
            ConditionalVideosConfig config = deserializeLenient(rootJson, skipped, fileKey);
            config.ensureDefaults();

            if (!skipped.isEmpty()) {
                if (shouldLogConfigIssue(fileKey, "skipped:" + String.join("|", skipped))) {
                    for (String warning : skipped) {
                        ConditionalVideos.LOGGER.warn("{}", warning);
                    }
                    ConditionalVideos.LOGGER.warn("Config '{}' had {} malformed entr(ies) that were skipped (kept defaults for those). The file is left UNTOUCHED so the rest of your configuration is preserved and you can fix the bad entr(ies). This is logged once until the file changes.", file, skipped.size());
                }
            } else {
                clearConfigIssueLog(fileKey);
                if (needsMigration) {
                    config.save(file);
                    ConditionalVideos.LOGGER.info("Migrated config '{}' from v{} to v{}.", file, loadedVersion, CURRENT_CONFIG_VERSION);
                }
            }
            return config;
        } catch (Throwable throwable) {
            if (shouldLogConfigIssue(fileKey, "error:" + throwable)) {
                ConditionalVideos.LOGGER.error("Unexpected error processing config '{}'. Using defaults for this session; the file is left UNTOUCHED so your configuration is not lost: {}", file, throwable.toString());
            }
            ConditionalVideosConfig fallback = new ConditionalVideosConfig();
            fallback.ensureDefaults();
            return fallback;
        }
    }

    private static ConditionalVideosConfig deserializeLenient(JsonObject root, List<String> skipped, String source) {
        ConditionalVideosConfig config = new ConditionalVideosConfig();
        config.firstJoin = parseConditionLenient(root, "firstJoin", defaultFirstJoin(), skipped, source);
        config.playerDeath = parseConditionLenient(root, "playerDeath", defaultPlayerDeath(), skipped, source);
        config.totemUsed = parseConditionLenient(root, "totemUsed", defaultSimpleCondition(), skipped, source);
        config.bedSleep = parseConditionLenient(root, "bedSleep", defaultSimpleCondition(), skipped, source);
        config.entityKilled = parseConditionMapLenient(root, "entityKilled", skipped, source);
        config.deathByEntity = parseConditionMapLenient(root, "deathByEntity", skipped, source);
        config.advancementCompleted = parseConditionMapLenient(root, "advancementCompleted", skipped, source);
        config.dimensionChanged = parseConditionMapLenient(root, "dimensionChanged", skipped, source);
        config.itemObtained = parseConditionMapLenient(root, "itemObtained", skipped, source);
        config.itemCrafted = parseConditionMapLenient(root, "itemCrafted", skipped, source);
        config.recipeUnlocked = parseConditionMapLenient(root, "recipeUnlocked", skipped, source);
        config.custom = parseConditionMapLenient(root, "custom", skipped, source);
        config.scoreboard = parseScoreboardMapLenient(root, "scoreboard", skipped, source);
        config.consumedConditionSessions = parseStringSetLenient(root, "consumedConditionSessions");
        return config;
    }

    private static ConditionConfig parseConditionLenient(JsonObject root, String key, ConditionConfig fallback,
                                                         List<String> skipped, String source) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            ConditionConfig parsed = GSON.fromJson(root.get(key), ConditionConfig.class);
            return parsed == null ? fallback : parsed;
        } catch (RuntimeException exception) {
            skipped.add("Condition '" + key + "' in '" + source + "' is malformed and was reset to defaults: " + exception.getMessage());
            return fallback;
        }
    }

    private static Map<String, ConditionConfig> parseConditionMapLenient(JsonObject root, String key,
                                                                         List<String> skipped, String source) {
        Map<String, ConditionConfig> map = new HashMap<>();
        if (!root.has(key) || !root.get(key).isJsonObject()) {
            return map;
        }
        for (Map.Entry<String, JsonElement> entry : root.get(key).getAsJsonObject().entrySet()) {
            try {
                ConditionConfig parsed = GSON.fromJson(entry.getValue(), ConditionConfig.class);
                if (parsed != null) {
                    map.put(entry.getKey(), parsed);
                }
            } catch (RuntimeException exception) {
                skipped.add("Condition '" + key + "/" + entry.getKey() + "' in '" + source + "' is malformed and was skipped: " + exception.getMessage());
            }
        }
        return map;
    }

    private static Map<String, ScoreboardConditionConfig> parseScoreboardMapLenient(JsonObject root, String key,
                                                                                    List<String> skipped, String source) {
        Map<String, ScoreboardConditionConfig> map = new HashMap<>();
        if (!root.has(key) || !root.get(key).isJsonObject()) {
            return map;
        }
        for (Map.Entry<String, JsonElement> entry : root.get(key).getAsJsonObject().entrySet()) {
            try {
                ScoreboardConditionConfig parsed = GSON.fromJson(entry.getValue(), ScoreboardConditionConfig.class);
                if (parsed != null) {
                    map.put(entry.getKey(), parsed);
                }
            } catch (RuntimeException exception) {
                skipped.add("Scoreboard condition '" + key + "/" + entry.getKey() + "' in '" + source + "' is malformed and was skipped: " + exception.getMessage());
            }
        }
        return map;
    }

    private static Set<String> parseStringSetLenient(JsonObject root, String key) {
        Set<String> set = new HashSet<>();
        if (root.has(key) && root.get(key).isJsonArray()) {
            for (JsonElement element : root.get(key).getAsJsonArray()) {
                try {
                    if (element != null && element.isJsonPrimitive()) {
                        set.add(element.getAsString());
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        return set;
    }

    private static int readConfigVersion(JsonObject root) {
        if (root.has("configVersion") && root.get("configVersion").isJsonPrimitive()) {
            try {
                return root.get("configVersion").getAsInt();
            } catch (Exception ignored) {
            }
        }
        return 1;
    }

    private static void backupBeforeMigrate(Path file, int oldVersion) {
        Path backup = file.resolveSibling(file.getFileName() + ".bak-v" + oldVersion);
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            ConditionalVideos.LOGGER.info("Backed up config '{}' to '{}' before migration.", file, backup);
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.warn("Failed to back up config '{}' before migration: {}", file, exception.toString());
        }
    }

    private static boolean hasLegacySchema(JsonObject root) {
        if (hasLegacyConditionFields(root, "firstJoin")) return true;
        if (hasLegacyConditionFields(root, "playerDeath")) return true;
        for (String mapKey : new String[]{"entityKilled", "deathByEntity", "advancementCompleted", "dimensionChanged"}) {
            if (!root.has(mapKey) || !root.get(mapKey).isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> e : root.get(mapKey).getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonObject() && conditionObjectHasLegacyFields(e.getValue().getAsJsonObject())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasLegacyConditionFields(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonObject()) return false;
        return conditionObjectHasLegacyFields(root.get(key).getAsJsonObject());
    }

    private static boolean conditionObjectHasLegacyFields(JsonObject obj) {
        if (obj.has("video")) return true;
        for (String field : LEGACY_DECORATOR_FIELDS) {
            if (obj.has(field)) return true;
        }
        return false;
    }

    private static void migrateLegacySchema(JsonObject root) {
        migrateConditionField(root, "firstJoin");
        migrateConditionField(root, "playerDeath");
        migrateConditionMapField(root, "entityKilled");
        migrateConditionMapField(root, "deathByEntity");
        migrateConditionMapField(root, "advancementCompleted");
        migrateConditionMapField(root, "dimensionChanged");
    }

    private static void migrateConditionField(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonObject()) {
            return;
        }
        JsonObject migrated = migrateSingleConditionObject(root.get(key).getAsJsonObject());
        root.add(key, migrated);
    }

    private static void migrateConditionMapField(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonObject()) {
            return;
        }
        JsonObject newMap = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : root.get(key).getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonObject()) {
                newMap.add(entry.getKey(), migrateSingleConditionObject(entry.getValue().getAsJsonObject()));
            }
        }
        root.add(key, newMap);
    }

    private static JsonObject migrateSingleConditionObject(JsonObject old) {
        JsonObject migrated = new JsonObject();
        if (old.has("repeatableInSameSession")) {
            migrated.add("repeatableInSameSession", old.get("repeatableInSameSession"));
        }
        if (old.has("playlistLoop")) {
            migrated.add("playlistLoop", old.get("playlistLoop"));
        }

        JsonObject decorators = new JsonObject();
        for (String fieldName : LEGACY_DECORATOR_FIELDS) {
            if (old.has(fieldName)) {
                decorators.add(fieldName, old.get(fieldName));
            }
        }

        JsonArray videos = new JsonArray();
        if (old.has("videos") && old.get("videos").isJsonArray()) {
            for (JsonElement element : old.get("videos").getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject merged = new JsonObject();
                for (Map.Entry<String, JsonElement> d : decorators.entrySet()) {
                    merged.add(d.getKey(), d.getValue());
                }
                for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                    merged.add(e.getKey(), e.getValue());
                }
                videos.add(merged);
            }
        } else if (old.has("video") && old.get("video").isJsonPrimitive()) {
            String videoPath = old.get("video").getAsString();
            if (videoPath != null && !videoPath.isBlank()) {
                JsonObject entry = new JsonObject();
                for (Map.Entry<String, JsonElement> d : decorators.entrySet()) {
                    entry.add(d.getKey(), d.getValue());
                }
                entry.addProperty("source", videoPath);
                videos.add(entry);
            }
        }
        migrated.add("videos", videos);

        return migrated;
    }

    public void save() {
        save(configPath(CLIENT_FILE_NAME));
    }

    public void saveServer(Path gameDirectory) {
        save(serverConfigPath(gameDirectory));
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(toJsonTree(), writer);
            }
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.error("Failed to save config file '{}'.", file, exception);
        }
    }

    // Custom serialization so the file stays tidy: only firstJoin ships its base options; every
    // other condition that is still at defaults (no videos, no flags) is written as an empty
    // object `{}` instead of the full {repeatableInSameSession, playlistLoop, videos} block.
    private JsonObject toJsonTree() {
        JsonObject root = new JsonObject();
        root.addProperty("configVersion", configVersion == null ? CURRENT_CONFIG_VERSION : configVersion);
        root.add("firstJoin", GSON.toJsonTree(firstJoin));
        root.add("playerDeath", conditionTree(playerDeath));
        root.add("entityKilled", conditionMapTree(entityKilled));
        root.add("deathByEntity", conditionMapTree(deathByEntity));
        root.add("advancementCompleted", conditionMapTree(advancementCompleted));
        root.add("dimensionChanged", conditionMapTree(dimensionChanged));
        root.add("totemUsed", conditionTree(totemUsed));
        root.add("bedSleep", conditionTree(bedSleep));
        root.add("itemObtained", conditionMapTree(itemObtained));
        root.add("itemCrafted", conditionMapTree(itemCrafted));
        root.add("recipeUnlocked", conditionMapTree(recipeUnlocked));
        root.add("scoreboard", GSON.toJsonTree(scoreboard));
        root.add("custom", conditionMapTree(custom));
        root.add("consumedConditionSessions", GSON.toJsonTree(consumedConditionSessions));
        return root;
    }

    private static JsonElement conditionTree(ConditionConfig config) {
        if (isEmptyCondition(config)) {
            return new JsonObject();
        }
        return GSON.toJsonTree(config);
    }

    private static JsonObject conditionMapTree(Map<String, ConditionConfig> map) {
        JsonObject object = new JsonObject();
        if (map != null) {
            for (Map.Entry<String, ConditionConfig> entry : map.entrySet()) {
                object.add(entry.getKey(), conditionTree(entry.getValue()));
            }
        }
        return object;
    }

    private static boolean isEmptyCondition(ConditionConfig config) {
        return config == null
                || (!config.repeatableInSameSession()
                && !config.resolvedPlaylistLoop()
                && config.resolvedPlaylist().isEmpty());
    }

    public int configVersion() {
        return configVersion == null ? 0 : configVersion;
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

    public ConditionConfig totemUsed() {
        return totemUsed;
    }

    public ConditionConfig bedSleep() {
        return bedSleep;
    }

    public Map<String, ConditionConfig> itemObtained() {
        return itemObtained;
    }

    public Map<String, ConditionConfig> itemCrafted() {
        return itemCrafted;
    }

    public Map<String, ConditionConfig> recipeUnlocked() {
        return recipeUnlocked;
    }

    public Map<String, ScoreboardConditionConfig> scoreboard() {
        return scoreboard;
    }

    public Map<String, ConditionConfig> custom() {
        return custom;
    }

    public boolean hasConsumedConditionSession(String conditionId, String sessionKey) {
        return consumedConditionSessions.contains(conditionSessionKey(conditionId, sessionKey));
    }

    public void markConditionSessionConsumed(String conditionId, String sessionKey) {
        consumedConditionSessions.add(conditionSessionKey(conditionId, sessionKey));
    }

    public int resolveBackgroundColor(VideoEntry entry, int fallbackColor) {
        if (entry == null || !entry.resolvedEnableBackground()) {
            return fallbackColor;
        }

        String hexColor = entry.resolvedColorBackground();
        if (hexColor.isBlank()) {
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

    private boolean ensureDefaults() {
        if (firstJoin == null) {
            firstJoin = defaultFirstJoin();
        }
        if (playerDeath == null) {
            playerDeath = defaultPlayerDeath();
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
        if (itemObtained == null) {
            itemObtained = new HashMap<>();
        }
        if (itemCrafted == null) {
            itemCrafted = new HashMap<>();
        }
        if (recipeUnlocked == null) {
            recipeUnlocked = new HashMap<>();
        }
        if (scoreboard == null) {
            scoreboard = new HashMap<>();
        }
        if (custom == null) {
            custom = new HashMap<>();
        }
        if (consumedConditionSessions == null) {
            consumedConditionSessions = new HashSet<>();
        }
        firstJoin = normaliseCondition(firstJoin);
        playerDeath = normaliseCondition(playerDeath);
        totemUsed = normaliseCondition(totemUsed);
        bedSleep = normaliseCondition(bedSleep);
        deathByEntity.replaceAll((key, value) -> normaliseCondition(value));
        entityKilled.replaceAll((key, value) -> normaliseCondition(value));
        advancementCompleted.replaceAll((key, value) -> normaliseCondition(value));
        dimensionChanged.replaceAll((key, value) -> normaliseCondition(value));
        itemObtained.replaceAll((key, value) -> normaliseCondition(value));
        itemCrafted.replaceAll((key, value) -> normaliseCondition(value));
        recipeUnlocked.replaceAll((key, value) -> normaliseCondition(value));
        custom.replaceAll((key, value) -> normaliseCondition(value));
        scoreboard.replaceAll((key, value) -> normaliseScoreboard(value));
        boolean migrated = configVersion == null || configVersion < CURRENT_CONFIG_VERSION;
        configVersion = CURRENT_CONFIG_VERSION;
        return migrated;
    }

    private static ConditionConfig normaliseCondition(ConditionConfig config) {
        if (config == null) {
            return baseDefaults();
        }
        List<VideoEntry> rawVideos = config.videos();
        List<VideoEntry> cleaned = new ArrayList<>();
        if (rawVideos != null) {
            for (VideoEntry entry : rawVideos) {
                if (entry == null) continue;
                if (entry.source() == null || entry.source().isBlank()) continue;
                cleaned.add(entry);
            }
        }
        return new ConditionConfig(
                config.repeatableInSameSession(),
                config.playlistLoop() == null ? Boolean.FALSE : config.playlistLoop(),
                cleaned
        );
    }

    private static ConditionConfig baseDefaults() {
        return new ConditionConfig(true, Boolean.FALSE, new ArrayList<>());
    }

    private static ConditionConfig defaultSimpleCondition() {
        return new ConditionConfig(false, Boolean.FALSE, new ArrayList<>());
    }

    public static ConditionConfig emptyConditionConfig() {
        return new ConditionConfig(false, Boolean.FALSE, new ArrayList<>());
    }

    private static ScoreboardConditionConfig normaliseScoreboard(ScoreboardConditionConfig config) {
        if (config == null) {
            return new ScoreboardConditionConfig(false, Boolean.FALSE, new ArrayList<>(), 0, null);
        }
        List<VideoEntry> rawVideos = config.videos();
        List<VideoEntry> cleaned = new ArrayList<>();
        if (rawVideos != null) {
            for (VideoEntry entry : rawVideos) {
                if (entry == null) continue;
                if (entry.source() == null || entry.source().isBlank()) continue;
                cleaned.add(entry);
            }
        }
        return new ScoreboardConditionConfig(
                config.repeatableInSameSession(),
                config.playlistLoop() == null ? Boolean.FALSE : config.playlistLoop(),
                cleaned,
                config.value(),
                config.comparator()
        );
    }

    private static ConditionConfig defaultFirstJoin() {
        return new ConditionConfig(false, Boolean.FALSE, new ArrayList<>());
    }

    private static ConditionConfig defaultPlayerDeath() {
        return emptyConditionConfig();
    }

    public String toJson() {
        return GSON.toJson(toJsonTree());
    }

    public static ConditionalVideosConfig fromJson(String rawJson) {
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            if (parsed == null || !parsed.isJsonObject()) {
                ConditionalVideosConfig fallback = new ConditionalVideosConfig();
                fallback.ensureDefaults();
                return fallback;
            }
            JsonObject root = parsed.getAsJsonObject();
            int version = readConfigVersion(root);
            if (version < CURRENT_CONFIG_VERSION || hasLegacySchema(root)) {
                migrateLegacySchema(root);
                root.addProperty("configVersion", CURRENT_CONFIG_VERSION);
            }
            List<String> skipped = new ArrayList<>();
            ConditionalVideosConfig config = deserializeLenient(root, skipped, "<synced server config>");
            config.ensureDefaults();
            for (String warning : skipped) {
                ConditionalVideos.LOGGER.warn("{}", warning);
            }
            return config;
        } catch (JsonSyntaxException exception) {
            ConditionalVideos.LOGGER.warn("Failed to parse synced config JSON. Falling back to defaults.");
            ConditionalVideosConfig fallback = new ConditionalVideosConfig();
            fallback.ensureDefaults();
            return fallback;
        }
    }

    private static Path configPath(String fileName) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(fileName);
    }

    public record ConditionConfig(
            boolean repeatableInSameSession,
            Boolean playlistLoop,
            List<VideoEntry> videos
    ) {
        public boolean resolvedPlaylistLoop() {
            return playlistLoop != null && playlistLoop;
        }

        public List<VideoEntry> resolvedPlaylist() {
            List<VideoEntry> list = new ArrayList<>();
            if (videos != null) {
                for (VideoEntry entry : videos) {
                    if (entry == null) continue;
                    if (entry.source() == null || entry.source().isBlank()) continue;
                    list.add(entry);
                }
            }
            return list;
        }
    }

    public record ScoreboardConditionConfig(
            boolean repeatableInSameSession,
            Boolean playlistLoop,
            List<VideoEntry> videos,
            Integer value,
            String comparator
    ) {
        public boolean resolvedPlaylistLoop() {
            return playlistLoop != null && playlistLoop;
        }

        public List<VideoEntry> resolvedPlaylist() {
            List<VideoEntry> list = new ArrayList<>();
            if (videos != null) {
                for (VideoEntry entry : videos) {
                    if (entry == null) continue;
                    if (entry.source() == null || entry.source().isBlank()) continue;
                    list.add(entry);
                }
            }
            return list;
        }

        public int resolvedValue() {
            return value == null ? 0 : value;
        }

        public String resolvedComparator() {
            if (comparator != null) {
                String trimmed = comparator.trim();
                for (String valid : VALID_COMPARATORS) {
                    if (valid.equalsIgnoreCase(trimmed)) {
                        return valid;
                    }
                }
            }
            return COMPARATOR_GREATER_OR_EQUAL;
        }

        public boolean matches(int score) {
            int target = resolvedValue();
            return switch (resolvedComparator()) {
                case COMPARATOR_EQUAL -> score == target;
                case COMPARATOR_LESS -> score < target;
                case COMPARATOR_GREATER -> score > target;
                case COMPARATOR_LESS_OR_EQUAL -> score <= target;
                default -> score >= target;
            };
        }

        public ConditionConfig toConditionConfig() {
            return new ConditionConfig(repeatableInSameSession, playlistLoop, videos);
        }
    }

    public record VideoEntry(
            String source,
            Boolean skippable,
            Boolean videoLoop,
            Boolean enableBackground,
            String colorBackground,
            String videoTitle,
            String videoTitlePosition,
            String videoDescription,
            String videoDescriptionPosition,
            Float titleTextScale,
            Float descriptionTextScale,
            Float textBoxOpacity,
            Float videoVolume,
            String transition,
            Float nextAt
    ) {
        public VideoEntry(String source) {
            this(source, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        public boolean resolvedSkippable() {
            if (resolvedVideoLoop()) {
                return true;
            }
            return skippable == null || skippable;
        }

        public boolean resolvedVideoLoop() {
            return videoLoop != null && videoLoop;
        }

        public boolean resolvedEnableBackground() {
            return enableBackground == null || enableBackground;
        }

        public String resolvedColorBackground() {
            return colorBackground == null ? "#000000" : colorBackground;
        }

        public String resolvedVideoTitle() {
            return videoTitle == null ? "" : videoTitle;
        }

        public String resolvedVideoTitlePosition() {
            return videoTitlePosition == null ? "bottomLeft" : videoTitlePosition;
        }

        public String resolvedVideoDescription() {
            return videoDescription == null ? "" : videoDescription;
        }

        public String resolvedVideoDescriptionPosition() {
            return videoDescriptionPosition == null ? "bottomLeft" : videoDescriptionPosition;
        }

        public float resolvedTitleScale() {
            return titleTextScale != null && titleTextScale > 0f ? titleTextScale : DEFAULT_TEXT_SCALE;
        }

        public float resolvedDescriptionScale() {
            return descriptionTextScale != null && descriptionTextScale > 0f ? descriptionTextScale : DEFAULT_TEXT_SCALE;
        }

        public float resolvedTextBoxOpacity() {
            if (textBoxOpacity == null) {
                return TEXT_BOX_OPACITY_LEGACY;
            }
            return Math.max(0f, Math.min(1f, textBoxOpacity));
        }

        public float resolvedVolume() {
            if (videoVolume == null) {
                return DEFAULT_VIDEO_VOLUME;
            }
            return Math.max(0f, Math.min(1f, videoVolume));
        }

        public String resolvedTransition() {
            if (transition == null) {
                return TRANSITION_CUT;
            }
            String trimmed = transition.trim();
            if (trimmed.isEmpty()) {
                return TRANSITION_CUT;
            }
            if (trimmed.equalsIgnoreCase(TRANSITION_FADE)) {
                return TRANSITION_FADE;
            }
            return TRANSITION_CUT;
        }

        public Float resolvedNextAt() {
            if (nextAt == null) {
                return null;
            }
            return nextAt > 0f ? nextAt : null;
        }
    }
}
