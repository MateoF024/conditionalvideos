package org.mateof24.conditionalvideos.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.architectury.platform.Platform;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.debug.DebugLog;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mateof24.conditionalvideos.config.ConditionalVideosConfig.FLAT_TYPES;
import static org.mateof24.conditionalvideos.config.ConditionalVideosConfig.LEGACY_DECORATOR_FIELDS;
import static org.mateof24.conditionalvideos.config.ConditionalVideosConfig.LEGACY_SHAPED;
import static org.mateof24.conditionalvideos.config.ConditionalVideosConfig.NAMESPACED_TYPES;
import static org.mateof24.conditionalvideos.config.ConditionalVideosConfig.SIMPLE_CONDITIONS;

// Moves the single-file configs of 1.5.1 and earlier (conditionalvideos.json and
// conditionalvideos-server.json) into one file per condition, and creates a clean folder on first run.
// The move is a literal copy: every value keeps its content and its written form, unknown fields included,
// and only the 1.1.x single-video shape is converted. It runs in a dot-named temporary folder, is checked
// member by member and only then becomes the real folder; any failure leaves the original untouched.
public final class LegacyMigration {
    private static final String CLIENT_FILE = "conditionalvideos.json";
    private static final String SERVER_FILE = "conditionalvideos-server.json";
    private static final List<ConfigNotice> NOTICES = new ArrayList<>();

    private LegacyMigration() {
    }

    // Problems found at startup, kept for the chat announcements of this game run.
    public static synchronized List<ConfigNotice> notices() {
        return List.copyOf(NOTICES);
    }

    // Runs once at startup on any side. Both old files are migrated wherever they are found; a missing
    // folder is created empty only for the side that uses it.
    public static void run(boolean dedicatedServer) {
        Path config = Platform.getGameFolder().resolve("config");
        migrate(config.resolve(CLIENT_FILE), ConditionFiles.client(), !dedicatedServer);
        migrate(config.resolve(SERVER_FILE), ConditionFiles.server(), dedicatedServer);
    }

    private static void migrate(Path legacy, ConditionFiles files, boolean createIfMissing) {
        Path target = files.directory();
        Path temporary = target.resolveSibling("." + target.getFileName() + ".migrating");
        String legacyLabel = "config/" + legacy.getFileName();
        String targetLabel = "config/" + ConditionalVideos.MOD_ID + "/" + files.folder() + "/";
        try {
            deleteTree(temporary);
        } catch (IOException exception) {
            fail(legacyLabel, "the temporary folder '" + temporary + "' could not be removed: " + ConfigNotice.reason(exception));
            return;
        }
        if (Files.exists(target)) {
            if (Files.isRegularFile(legacy)) {
                notice(ConfigNotice.legacyIgnored(legacyLabel, targetLabel));
            }
            return;
        }
        if (Files.isRegularFile(legacy)) {
            migrateFile(legacy, legacyLabel, target, targetLabel, temporary);
        } else if (createIfMissing) {
            createClean(target, targetLabel, temporary);
        }
    }

    private static void migrateFile(Path legacy, String legacyLabel, Path target, String targetLabel, Path temporary) {
        long start = System.currentTimeMillis();
        DebugLog.side(DebugLog.Area.MIGRATION, "Migrating '{}' into '{}'.", legacy, target);
        String text;
        JsonElement parsed;
        try {
            text = ConditionFiles.readText(legacy);
            parsed = text.isBlank() ? null : JsonParser.parseString(text);
        } catch (IOException | RuntimeException exception) {
            notice(ConfigNotice.migrationSuspended(legacyLabel, exception));
            DebugLog.side(DebugLog.Area.MIGRATION, "Suspended: '{}' could not be parsed ({}).", legacy, ConfigNotice.reason(exception));
            return;
        }
        if (parsed == null || !parsed.isJsonObject()) {
            notice(ConfigNotice.migrationNotObject(legacyLabel));
            DebugLog.side(DebugLog.Area.MIGRATION, "Suspended: '{}' is not a JSON object.", legacy);
            return;
        }
        JsonObject root = parsed.getAsJsonObject();

        Plan plan;
        try {
            plan = plan(root);
            plan.verify();
        } catch (RuntimeException exception) {
            fail(legacyLabel, "the split could not be verified: " + ConfigNotice.reason(exception));
            return;
        }
        DebugLog.side(DebugLog.Area.MIGRATION, "Split into {} file(s) ({} converted from the 1.1.x format, {} with an \"id\", {} unrecognized entr(ies)); skipped: {}.",
                plan.files.size(), plan.converted, plan.withId, plan.unrecognizedCount, plan.skipped);

        try {
            write(plan.files, temporary);
            DebugLog.side(DebugLog.Area.MIGRATION, "Wrote and re-read {} file(s) in '{}'; every member matches the original.",
                    plan.files.size(), temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException exception) {
            try {
                deleteTree(temporary);
            } catch (IOException ignored) {
            }
            fail(legacyLabel, ConfigNotice.reason(exception));
            return;
        }

        Path backup = legacy.resolveSibling(legacy.getFileName() + ".bak");
        for (int n = 2; Files.exists(backup); n++) {
            backup = legacy.resolveSibling(legacy.getFileName() + ".bak" + n);
        }
        try {
            Files.move(legacy, backup);
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.error("'{}' was migrated into '{}' but could not be renamed to '{}': {}. It stays where it is and will be reported as ignored.",
                    legacy, target, backup.getFileName(), ConfigNotice.reason(exception));
            return;
        }
        ConditionalVideos.LOGGER.info("Migrated '{}' into {} file(s) in '{}'; the original was kept as '{}'.",
                legacy, plan.files.size(), target, backup.getFileName());
        if (isNonStandard(text)) {
            ConditionalVideos.LOGGER.info("'{}' used comments or other non-standard JSON; the new files are standard JSON and the original text remains in '{}'.",
                    legacy.getFileName(), backup.getFileName());
        }
        if (plan.unrecognizedCount > 0) {
            notice(ConfigNotice.unrecognized(legacyLabel, plan.unrecognizedCount, targetLabel + ConditionFiles.UNRECOGNIZED_FILE));
        }
        DebugLog.side(DebugLog.Area.MIGRATION, "Done in {} ms; '{}' is now '{}'.", System.currentTimeMillis() - start,
                legacy.getFileName(), backup.getFileName());
    }

    private static void createClean(Path target, String targetLabel, Path temporary) {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (String name : SIMPLE_CONDITIONS) {
            JsonObject condition = new JsonObject();
            if (name.equals("firstJoin")) {
                condition.addProperty("repeatableInSameSession", false);
                condition.addProperty("playlistLoop", false);
                condition.add("videos", new JsonArray());
            }
            files.put(name + ".json", condition);
        }
        try {
            write(files, temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            DebugLog.side(DebugLog.Area.MIGRATION, "Created the empty conditions folder '{}'.", target);
        } catch (IOException | RuntimeException exception) {
            try {
                deleteTree(temporary);
            } catch (IOException ignored) {
            }
            ConditionalVideos.LOGGER.error("Could not create the conditions folder '{}': {}", targetLabel, ConfigNotice.reason(exception));
        }
    }

    // Splits the old root object into files. Each entry remembers the original value it came from.
    private static Plan plan(JsonObject root) {
        Plan plan = new Plan();
        ConditionFiles.Layout layout = new ConditionFiles.Layout();
        for (String name : SIMPLE_CONDITIONS) {
            layout.claim(name + ".json");
        }
        for (String type : NAMESPACED_TYPES) {
            layout.claim(type + "/");
        }
        for (String type : FLAT_TYPES) {
            layout.claim(type + "/");
        }
        layout.claim(ConditionFiles.UNRECOGNIZED_FILE);
        JsonObject unrecognized = new JsonObject();
        for (Map.Entry<String, JsonElement> member : root.entrySet()) {
            String name = member.getKey();
            JsonElement value = member.getValue();
            if (name.equals("configVersion") || name.equals("consumedConditionSessions")) {
                plan.skipped.add(name);
            } else if (SIMPLE_CONDITIONS.contains(name)) {
                plan.add(name + ".json", name, value, null);
            } else if ((NAMESPACED_TYPES.contains(name) || FLAT_TYPES.contains(name)) && value.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                    String key = entry.getKey();
                    JsonElement condition = entry.getValue();
                    if (!fitsInFile(layout, name, key, condition)) {
                        if (!unrecognized.has(name)) {
                            unrecognized.add(name, new JsonObject());
                        }
                        unrecognized.getAsJsonObject(name).add(key, condition);
                        plan.unrecognizedCount++;
                        continue;
                    }
                    ConditionFiles.Layout.Placement placement = layout.place(name, key);
                    plan.add(placement.path(), name, condition, placement.needsId() ? key : null);
                }
            } else {
                unrecognized.add(name, value);
                plan.unrecognizedCount++;
            }
        }
        if (unrecognized.size() > 0) {
            plan.files.put(ConditionFiles.UNRECOGNIZED_FILE, unrecognized);
            plan.originals.put(ConditionFiles.UNRECOGNIZED_FILE, new Origin(unrecognized, false, null, false));
        }
        return plan;
    }

    // A condition keeps its own file unless an "id" member would point it to another key, or it needs an
    // "id" and is not an object that could hold one.
    private static boolean fitsInFile(ConditionFiles.Layout layout, String type, String key, JsonElement condition) {
        if (condition.isJsonObject() && condition.getAsJsonObject().has("id")) {
            return condition.getAsJsonObject().get("id").equals(new JsonPrimitive(key));
        }
        return condition.isJsonObject() || !layout.wouldNeedId(type, key);
    }

    private static void write(Map<String, JsonElement> files, Path temporary) throws IOException {
        Files.createDirectories(temporary);
        for (String type : NAMESPACED_TYPES) {
            Files.createDirectories(temporary.resolve(type));
        }
        for (String type : FLAT_TYPES) {
            Files.createDirectories(temporary.resolve(type));
        }
        for (Map.Entry<String, JsonElement> file : files.entrySet()) {
            Path path = temporary.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(Files.createFile(path), ConditionFiles.PRETTY.toJson(file.getValue()) + "\n");
        }
        for (Map.Entry<String, JsonElement> file : files.entrySet()) {
            String written = ConditionFiles.readText(temporary.resolve(file.getKey()));
            if (!compact(JsonParser.parseString(written)).equals(compact(file.getValue()))) {
                throw new IOException("'" + file.getKey() + "' does not read back as it was written");
            }
        }
    }

    private static String compact(JsonElement element) {
        return ConditionFiles.COMPACT.toJson(element);
    }

    // True when the text only parses leniently (comments, single quotes, unquoted names...).
    private static boolean isNonStandard(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            ConditionFiles.COMPACT.getAdapter(JsonElement.class).read(reader);
            return reader.peek() != JsonToken.END_DOCUMENT;
        } catch (IOException | RuntimeException exception) {
            return true;
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void fail(String legacyLabel, String reason) {
        notice(ConfigNotice.migrationFailed(legacyLabel, reason));
        DebugLog.side(DebugLog.Area.MIGRATION, "Failed for '{}': {}", legacyLabel, reason);
    }

    private static synchronized void notice(ConfigNotice notice) {
        NOTICES.add(notice);
        ConditionalVideos.LOGGER.warn("{}", notice.text());
    }

    // Where a planned file came from: the original value, whether it was converted from the 1.1.x shape and
    // the exact key added as "id" (null when none).
    private record Origin(JsonElement original, boolean legacy, String id, boolean pinned) {
    }

    // In 1.5.1 a condition with videos that did not say repeatableInSameSession played once. The default is no longer
    // that for most types, so the value it worked with is written down and it keeps playing the way it did.
    private static boolean playedOnce(String type, JsonElement content) {
        if (!content.isJsonObject() || "firstJoin".equals(type)) {
            return false;
        }
        JsonObject condition = content.getAsJsonObject();
        JsonElement videos = condition.get("videos");
        return !condition.has("repeatableInSameSession") && videos != null && videos.isJsonArray() && videos.getAsJsonArray().size() > 0;
    }

    private static final class Plan {
        private final Map<String, JsonElement> files = new LinkedHashMap<>();
        private final Map<String, Origin> originals = new LinkedHashMap<>();
        private final List<String> skipped = new ArrayList<>();
        private int converted;
        private int withId;
        private int unrecognizedCount;

        private void add(String path, String type, JsonElement original, String id) {
            JsonElement content = original;
            boolean legacy = false;
            if (LEGACY_SHAPED.contains(type) && original.isJsonObject()) {
                JsonObject convertedObject = ConditionalVideosConfig.convertLegacy(original.getAsJsonObject());
                legacy = convertedObject != original;
                content = convertedObject;
            }
            if (id != null && !content.getAsJsonObject().has("id")) {
                JsonObject withIdObject = new JsonObject();
                withIdObject.addProperty("id", id);
                for (Map.Entry<String, JsonElement> member : content.getAsJsonObject().entrySet()) {
                    withIdObject.add(member.getKey(), member.getValue());
                }
                content = withIdObject;
                withId++;
            } else {
                id = null;
            }
            if (legacy) {
                converted++;
            }
            boolean pinned = playedOnce(type, content);
            if (pinned) {
                JsonObject withValue = new JsonObject();
                content.getAsJsonObject().entrySet().stream().filter(member -> member.getKey().equals("id"))
                        .forEach(member -> withValue.add(member.getKey(), member.getValue()));
                withValue.addProperty("repeatableInSameSession", false);
                for (Map.Entry<String, JsonElement> member : content.getAsJsonObject().entrySet()) {
                    withValue.add(member.getKey(), member.getValue());
                }
                content = withValue;
            }
            files.put(path, content);
            originals.put(path, new Origin(original, legacy, id, pinned));
        }

        // Checks every planned file against the value it came from: identical, apart from the added "id", the
        // written repeatableInSameSession and the documented 1.1.x conversion, whose every original value must
        // still be found in place.
        private void verify() {
            for (Map.Entry<String, JsonElement> file : files.entrySet()) {
                Origin origin = originals.get(file.getKey());
                JsonElement content = file.getValue();
                if (origin.id() != null) {
                    JsonObject withoutId = content.getAsJsonObject().deepCopy();
                    withoutId.remove("id");
                    content = withoutId;
                }
                if (origin.pinned()) {
                    JsonObject withoutValue = content.getAsJsonObject().deepCopy();
                    withoutValue.remove("repeatableInSameSession");
                    content = withoutValue;
                }
                boolean same = origin.legacy()
                        ? legacyPreserved(origin.original().getAsJsonObject(), content.getAsJsonObject())
                        : compact(content).equals(compact(origin.original()));
                if (!same) {
                    throw new IllegalStateException("'" + file.getKey() + "' does not match the original value");
                }
            }
        }
    }

    // Every value of a 1.1.x condition must be found in the converted one: its own members unchanged, each
    // original video unchanged, "video" as the source of the single entry, and every condition-level video
    // option inside each converted video unless that video set its own value.
    private static boolean legacyPreserved(JsonObject original, JsonObject converted) {
        JsonElement videos = converted.get("videos");
        if (videos == null || !videos.isJsonArray()) {
            return false;
        }
        JsonArray after = videos.getAsJsonArray();
        JsonElement beforeVideos = original.get("videos");
        if (beforeVideos != null && beforeVideos.isJsonNull()) {
            beforeVideos = null;
        }
        List<JsonObject[]> pairs = new ArrayList<>();
        if (beforeVideos != null) {
            JsonArray before = beforeVideos.getAsJsonArray();
            if (before.size() != after.size()) {
                return false;
            }
            for (int i = 0; i < before.size(); i++) {
                JsonElement old = before.get(i);
                JsonElement now = after.get(i);
                if (!old.isJsonObject()) {
                    if (!same(now, old)) {
                        return false;
                    }
                    continue;
                }
                if (!now.isJsonObject()) {
                    return false;
                }
                for (Map.Entry<String, JsonElement> member : old.getAsJsonObject().entrySet()) {
                    if (!same(now.getAsJsonObject().get(member.getKey()), member.getValue())) {
                        return false;
                    }
                }
                pairs.add(new JsonObject[]{old.getAsJsonObject(), now.getAsJsonObject()});
            }
        } else {
            if (after.size() != 1 || !after.get(0).isJsonObject()
                    || !same(after.get(0).getAsJsonObject().get("source"), original.get("video"))) {
                return false;
            }
            pairs.add(new JsonObject[]{null, after.get(0).getAsJsonObject()});
        }
        for (Map.Entry<String, JsonElement> member : original.entrySet()) {
            String name = member.getKey();
            if (name.equals("videos") || (beforeVideos == null && name.equals("video"))) {
                continue;
            }
            if (LEGACY_DECORATOR_FIELDS.contains(name)) {
                for (JsonObject[] pair : pairs) {
                    boolean ownValue = pair[0] != null && pair[0].has(name);
                    if (!ownValue && !same(pair[1].get(name), member.getValue())) {
                        return false;
                    }
                }
                continue;
            }
            if (!same(converted.get(name), member.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(JsonElement actual, JsonElement expected) {
        return actual != null && compact(actual).equals(compact(expected));
    }
}
