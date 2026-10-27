package org.mateof24.conditionalvideos.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.mateof24.conditionalvideos.config.ConditionFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Finds the conditions a singleplayer world had already watched in 1.5.1, which kept them in
// conditionalvideos.json (now its .bak) as "<condition>:world:<game folder>/saves/<world name>", and
// turns them into the condition keys used now.
public final class LegacyWatchedImport {
    private static final Pattern SOURCE = Pattern.compile("conditionalvideos\\.json(\\.bak\\d*)?");
    private static final String WORLD_SESSION = ":world:";
    private static final Map<String, String> RENAMED = Map.of(
            "advancementCompleted:", "advancement/",
            "dimensionChanged:", "dimension/",
            "deathByEntity:", "deathByEntity/",
            "entityKilled:", "entityKilled/");

    private LegacyWatchedImport() {
    }

    public record Result(List<String> keys, List<String> sources) {
    }

    // The game folder is matched only by its ending (saves/<world name>), so an instance that was moved
    // or launched with another path still finds its worlds. Unreadable files are skipped.
    public static Result collect(Path configDirectory, String levelName) {
        String tail = tail(levelName);
        Set<String> keys = new LinkedHashSet<>();
        List<String> sources = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> list = Files.list(configDirectory)) {
            files = list.filter(path -> SOURCE.matcher(path.getFileName().toString()).matches()).sorted().toList();
        } catch (IOException exception) {
            return new Result(List.of(), List.of());
        }
        for (Path file : files) {
            try {
                JsonElement root = JsonParser.parseString(ConditionFiles.readText(file));
                JsonElement sessions = root.isJsonObject() ? root.getAsJsonObject().get("consumedConditionSessions") : null;
                if (sessions == null || !sessions.isJsonArray()) {
                    continue;
                }
                boolean used = false;
                for (JsonElement session : sessions.getAsJsonArray()) {
                    if (!session.isJsonPrimitive()) {
                        continue;
                    }
                    String entry = session.getAsString();
                    int at = entry.lastIndexOf(WORLD_SESSION);
                    String path = at > 0 ? entry.substring(at + WORLD_SESSION.length()).replace('\\', '/') : "";
                    if (at > 0 && (path.equals(tail) || path.endsWith("/" + tail))) {
                        keys.add(rename(entry.substring(0, at)));
                        used = true;
                    }
                }
                if (used) {
                    sources.add(file.getFileName().toString());
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return new Result(List.copyOf(keys), sources);
    }

    private static String rename(String oldId) {
        for (Map.Entry<String, String> prefix : RENAMED.entrySet()) {
            if (oldId.startsWith(prefix.getKey())) {
                return prefix.getValue() + oldId.substring(prefix.getKey().length());
            }
        }
        return oldId;
    }

    // Same normalisation 1.5.1 applied to <game folder>/saves/<world name>.
    private static String tail(String levelName) {
        try {
            return Path.of("saves").resolve(levelName).normalize().toString().replace('\\', '/');
        } catch (InvalidPathException exception) {
            return "saves/" + levelName;
        }
    }
}
