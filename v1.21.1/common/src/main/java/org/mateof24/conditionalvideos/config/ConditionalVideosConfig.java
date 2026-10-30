package org.mateof24.conditionalvideos.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mateof24.conditionalvideos.ConditionalVideos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// In-memory view of every condition, built from the per-condition files (ConditionFiles) or from the
// aggregated JSON a server sends. It never touches the disk.
public final class ConditionalVideosConfig {
    private static final Gson GSON = new Gson();

    public static final int CURRENT_CONFIG_VERSION = 4;
    static final float DEFAULT_TEXT_SCALE = 1.0f;
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

    // Condition names as they appear in the files, the folders and the synced JSON.
    public static final List<String> SIMPLE_CONDITIONS = List.of("firstJoin", "playerDeath", "totemUsed", "bedSleep");
    public static final List<String> NAMESPACED_TYPES = List.of("entityKilled", "deathByEntity", "advancementCompleted",
            "dimensionChanged", "itemObtained", "itemCrafted", "recipeUnlocked");
    public static final List<String> FLAT_TYPES = List.of("scoreboard", "custom");
    // The conditions that existed in 1.1.x and may still use its single-video format.
    static final Set<String> LEGACY_SHAPED = Set.of("firstJoin", "playerDeath", "entityKilled", "deathByEntity",
            "advancementCompleted", "dimensionChanged");
    static final Set<String> LEGACY_DECORATOR_FIELDS = Set.of(
            "skippable", "videoLoop", "enableBackground", "colorBackground",
            "videoTitle", "videoTitlePosition",
            "videoDescription", "videoDescriptionPosition",
            "titleTextScale", "descriptionTextScale", "textBoxOpacity",
            "videoVolume", "transition", "nextAt"
    );

    private ConditionConfig firstJoin = emptyCondition();
    private ConditionConfig playerDeath = emptyCondition();
    private ConditionConfig totemUsed = emptyCondition();
    private ConditionConfig bedSleep = emptyCondition();
    private final Map<String, ConditionConfig> entityKilled = new HashMap<>();
    private final Map<String, ConditionConfig> deathByEntity = new HashMap<>();
    private final Map<String, ConditionConfig> advancementCompleted = new HashMap<>();
    private final Map<String, ConditionConfig> dimensionChanged = new HashMap<>();
    private final Map<String, ConditionConfig> itemObtained = new HashMap<>();
    private final Map<String, ConditionConfig> itemCrafted = new HashMap<>();
    private final Map<String, ConditionConfig> recipeUnlocked = new HashMap<>();
    private final Map<String, ScoreboardConditionConfig> scoreboard = new HashMap<>();
    private final Map<String, ConditionConfig> custom = new HashMap<>();

    // Reads one condition into this config. Returns false when it is skipped; every problem found,
    // fatal or not, is added to notices with the given file label.
    public boolean accept(String type, String key, JsonElement element, String file, List<ConfigNotice> notices) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (!element.isJsonObject()) {
            notices.add(ConfigNotice.notObject(file));
            return false;
        }
        JsonObject object = LEGACY_SHAPED.contains(type) ? convertLegacy(element.getAsJsonObject()) : element.getAsJsonObject();
        boolean isScoreboard = "scoreboard".equals(type);
        Object parsed;
        try {
            parsed = GSON.fromJson(object, isScoreboard ? ScoreboardConditionConfig.class : ConditionConfig.class);
        } catch (RuntimeException exception) {
            notices.add(ConfigNotice.invalidContent(file, locate(object, exception)));
            return false;
        }
        if (parsed == null) {
            return false;
        }
        if (parsed instanceof ScoreboardConditionConfig score) {
            lint(score.videos(), file, notices);
            if (score.comparator() != null && !score.comparator().isBlank() && !isValidComparator(score.comparator())) {
                notices.add(ConfigNotice.invalidComparator(file, score.comparator()));
            }
            scoreboard.put(key, new ScoreboardConditionConfig(score.repeatableInSameSession(),
                    score.playlistLoop() == null ? Boolean.FALSE : score.playlistLoop(), playable(score.videos()),
                    score.value(), score.comparator(), score.protectPlayer(), score.freezeWorld()));
            return true;
        }
        ConditionConfig condition = (ConditionConfig) parsed;
        lint(condition.videos(), file, notices);
        ConditionConfig normalised = new ConditionConfig(condition.repeatableInSameSession(),
                condition.playlistLoop() == null ? Boolean.FALSE : condition.playlistLoop(), playable(condition.videos()),
                condition.protectPlayer(), condition.freezeWorld() == null && "firstJoin".equals(type) ? Boolean.TRUE : condition.freezeWorld());
        switch (type) {
            case "firstJoin" -> firstJoin = normalised;
            case "playerDeath" -> playerDeath = normalised;
            case "totemUsed" -> totemUsed = normalised;
            case "bedSleep" -> bedSleep = normalised;
            default -> {
                Map<String, ConditionConfig> map = conditionMap(type);
                if (map == null) {
                    return false;
                }
                map.put(key, normalised);
            }
        }
        return true;
    }

    private Map<String, ConditionConfig> conditionMap(String type) {
        return switch (type) {
            case "entityKilled" -> entityKilled;
            case "deathByEntity" -> deathByEntity;
            case "advancementCompleted" -> advancementCompleted;
            case "dimensionChanged" -> dimensionChanged;
            case "itemObtained" -> itemObtained;
            case "itemCrafted" -> itemCrafted;
            case "recipeUnlocked" -> recipeUnlocked;
            case "custom" -> custom;
            default -> null;
        };
    }

    // Points at the first broken video when a condition fails to parse, so the notice says where to look.
    private static String locate(JsonObject object, RuntimeException exception) {
        JsonElement videos = object.get("videos");
        if (videos != null && videos.isJsonArray()) {
            int index = 0;
            for (JsonElement video : videos.getAsJsonArray()) {
                index++;
                try {
                    GSON.fromJson(video, VideoEntry.class);
                } catch (RuntimeException videoError) {
                    return "video " + index + ": " + ConfigNotice.reason(videoError);
                }
            }
        }
        return ConfigNotice.reason(exception);
    }

    // Values that still play but fall back to a default; the video number matches its place in the file.
    private static void lint(List<VideoEntry> videos, String file, List<ConfigNotice> notices) {
        if (videos == null) {
            return;
        }
        for (int i = 0; i < videos.size(); i++) {
            VideoEntry video = videos.get(i);
            if (video == null) {
                continue;
            }
            int number = i + 1;
            String color = video.colorBackground();
            if (color != null && !color.isBlank() && parseColor(color) == null) {
                notices.add(ConfigNotice.invalidOption(file, "colorBackground", color, number, "#000000"));
            }
            if (!isValidPosition(video.videoTitlePosition())) {
                notices.add(ConfigNotice.invalidOption(file, "videoTitlePosition", video.videoTitlePosition(), number, "bottomLeft"));
            }
            if (!isValidPosition(video.videoDescriptionPosition())) {
                notices.add(ConfigNotice.invalidOption(file, "videoDescriptionPosition", video.videoDescriptionPosition(), number, "bottomLeft"));
            }
            String transition = video.transition();
            if (transition != null && !transition.isBlank() && !transition.trim().equalsIgnoreCase(TRANSITION_CUT)
                    && !transition.trim().equalsIgnoreCase(TRANSITION_FADE)) {
                notices.add(ConfigNotice.invalidOption(file, "transition", transition, number, TRANSITION_CUT));
            }
            if (video.videoVolume() != null && (video.videoVolume() < 0f || video.videoVolume() > 1f)) {
                notices.add(ConfigNotice.outOfRange(file, "videoVolume", video.videoVolume(), number, "0-1"));
            }
            if (video.textBoxOpacity() != null && (video.textBoxOpacity() < 0f || video.textBoxOpacity() > 1f)) {
                notices.add(ConfigNotice.outOfRange(file, "textBoxOpacity", video.textBoxOpacity(), number, "0-1"));
            }
            if (video.titleTextScale() != null && video.titleTextScale() <= 0f) {
                notices.add(ConfigNotice.outOfRange(file, "titleTextScale", video.titleTextScale(), number, "> 0"));
            }
            if (video.descriptionTextScale() != null && video.descriptionTextScale() <= 0f) {
                notices.add(ConfigNotice.outOfRange(file, "descriptionTextScale", video.descriptionTextScale(), number, "> 0"));
            }
        }
    }

    private static List<VideoEntry> playable(List<VideoEntry> videos) {
        List<VideoEntry> cleaned = new ArrayList<>();
        if (videos != null) {
            for (VideoEntry entry : videos) {
                if (entry != null && entry.source() != null && !entry.source().isBlank()) {
                    cleaned.add(entry);
                }
            }
        }
        return cleaned;
    }

    static boolean isLegacy(JsonObject condition) {
        if (condition.has("video")) {
            return true;
        }
        for (String field : LEGACY_DECORATOR_FIELDS) {
            if (condition.has(field)) {
                return true;
            }
        }
        return false;
    }

    // Converts the 1.1.x shape (one "video" plus condition-level video options) into the playlist shape
    // without losing anything: options are copied into each video (a video's own value wins), "video"
    // becomes the first entry's source, and whatever cannot be placed stays where it was.
    static JsonObject convertLegacy(JsonObject old) {
        if (!isLegacy(old)) {
            return old;
        }
        JsonObject decorators = new JsonObject();
        for (Map.Entry<String, JsonElement> member : old.entrySet()) {
            if (LEGACY_DECORATOR_FIELDS.contains(member.getKey())) {
                decorators.add(member.getKey(), member.getValue());
            }
        }
        JsonElement videos = old.get("videos");
        if (videos != null && videos.isJsonNull()) {
            videos = null;
        }
        JsonArray converted = new JsonArray();
        boolean consumedVideo = false;
        boolean consumedDecorators = false;
        if (videos != null && videos.isJsonArray()) {
            for (JsonElement element : videos.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject merged = decorators.deepCopy();
                    for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet()) {
                        merged.add(member.getKey(), member.getValue());
                    }
                    converted.add(merged);
                    consumedDecorators = true;
                } else {
                    converted.add(element);
                }
            }
        } else if (videos == null && old.has("video")) {
            JsonObject entry = decorators.deepCopy();
            entry.add("source", old.get("video"));
            converted.add(entry);
            consumedVideo = true;
            consumedDecorators = true;
        } else {
            return old;
        }
        if (!consumedDecorators) {
            return old;
        }
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> member : old.entrySet()) {
            String name = member.getKey();
            if (LEGACY_DECORATOR_FIELDS.contains(name)) {
                continue;
            }
            if (name.equals("videos") || (consumedVideo && name.equals("video"))) {
                result.add("videos", converted);
                continue;
            }
            result.add(name, member.getValue());
        }
        return result;
    }

    // Aggregated JSON sent to clients: one object with every condition, the shape older versions read.
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("configVersion", CURRENT_CONFIG_VERSION);
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
        return GSON.toJson(root);
    }

    public static ConditionalVideosConfig fromJson(String rawJson) {
        ConditionalVideosConfig config = new ConditionalVideosConfig();
        List<ConfigNotice> notices = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                for (String name : SIMPLE_CONDITIONS) {
                    config.accept(name, name, root.get(name), name, notices);
                }
                for (String type : NAMESPACED_TYPES) {
                    config.acceptMap(type, root.get(type), notices);
                }
                for (String type : FLAT_TYPES) {
                    config.acceptMap(type, root.get(type), notices);
                }
            }
        } catch (RuntimeException exception) {
            ConditionalVideos.LOGGER.warn("Could not read the server's conditions; none are used: {}", ConfigNotice.reason(exception));
        }
        for (ConfigNotice notice : notices) {
            ConditionalVideos.LOGGER.warn("Server conditions: {}", notice.text());
        }
        return config;
    }

    private void acceptMap(String type, JsonElement element, List<ConfigNotice> notices) {
        if (element != null && element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                accept(type, entry.getKey(), entry.getValue(), type + "/" + entry.getKey(), notices);
            }
        }
    }

    private static JsonElement conditionTree(ConditionConfig config) {
        if (isEmptyCondition(config)) {
            return new JsonObject();
        }
        return GSON.toJsonTree(config);
    }

    private static JsonObject conditionMapTree(Map<String, ConditionConfig> map) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, ConditionConfig> entry : map.entrySet()) {
            object.add(entry.getKey(), conditionTree(entry.getValue()));
        }
        return object;
    }

    private static boolean isEmptyCondition(ConditionConfig config) {
        return config == null
                || (!config.repeatableInSameSession()
                && !config.resolvedPlaylistLoop()
                && config.resolvedPlaylist().isEmpty());
    }

    private static ConditionConfig emptyCondition() {
        return new ConditionConfig(false, Boolean.FALSE, new ArrayList<>(), null, null);
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

    public int resolveBackgroundColor(VideoEntry entry, int fallbackColor) {
        if (entry == null || !entry.resolvedEnableBackground()) {
            return fallbackColor;
        }
        Integer color = parseColor(entry.resolvedColorBackground());
        return color == null ? fallbackColor : color;
    }

    // "#RRGGBB" or "#AARRGGBB" (the '#' is optional); null when blank or invalid.
    static Integer parseColor(String hexColor) {
        if (hexColor == null || hexColor.isBlank()) {
            return null;
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
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static boolean isValidPosition(String position) {
        return TextPosition.parse(position) != null;
    }

    private static boolean isValidComparator(String comparator) {
        String trimmed = comparator.trim();
        for (String valid : VALID_COMPARATORS) {
            if (valid.equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    // protectPlayer is on unless set to false; freezeWorld is off unless set (firstJoin turns it on when it is missing).
    public record ConditionConfig(
            boolean repeatableInSameSession,
            Boolean playlistLoop,
            List<VideoEntry> videos,
            Boolean protectPlayer,
            Boolean freezeWorld
    ) {
        public boolean resolvedPlaylistLoop() {
            return playlistLoop != null && playlistLoop;
        }

        public List<VideoEntry> resolvedPlaylist() {
            return playable(videos);
        }

        public boolean resolvedProtectPlayer() {
            return protectPlayer == null || protectPlayer;
        }

        public boolean resolvedFreezeWorld() {
            return freezeWorld != null && freezeWorld;
        }
    }

    public record ScoreboardConditionConfig(
            boolean repeatableInSameSession,
            Boolean playlistLoop,
            List<VideoEntry> videos,
            Integer value,
            String comparator,
            Boolean protectPlayer,
            Boolean freezeWorld
    ) {
        public boolean resolvedPlaylistLoop() {
            return playlistLoop != null && playlistLoop;
        }

        public List<VideoEntry> resolvedPlaylist() {
            return playable(videos);
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
            return new ConditionConfig(repeatableInSameSession, playlistLoop, videos, protectPlayer, freezeWorld);
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

        public float resolvedVolume(float defaultVolume) {
            if (videoVolume == null) {
                return defaultVolume;
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
