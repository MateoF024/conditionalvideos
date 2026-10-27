package org.mateof24.conditionalvideos.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.debug.DebugLog;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.mateof24.conditionalvideos.config.ConditionalVideosConfig.FLAT_TYPES;
import static org.mateof24.conditionalvideos.config.ConditionalVideosConfig.NAMESPACED_TYPES;
import static org.mateof24.conditionalvideos.config.ConditionalVideosConfig.SIMPLE_CONDITIONS;

// One folder of per-condition files: conditions/ (client and singleplayer) or server-conditions/
// (dedicated server). It builds the in-memory config from the files, re-reads them only when one is
// added, removed or modified, and keeps the problems found so they can be announced. The only write is
// creating a missing file for a custom condition registered through the API.
public final class ConditionFiles {
    public static final String CLIENT_FOLDER = "conditions";
    public static final String SERVER_FOLDER = "server-conditions";
    public static final String UNRECOGNIZED_FILE = "unrecognized.json";
    private static final long RECHECK_MILLIS = 2000L;
    static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private static ConditionFiles client;
    private static ConditionFiles server;

    private final Path directory;
    private final String folder;
    private ConditionalVideosConfig config = new ConditionalVideosConfig();
    private List<ConfigNotice> notices = List.of();
    private Set<String> owned = Set.of();
    private TreeMap<String, String> scanned = new TreeMap<>();
    private String fingerprint;
    private long nextCheckMillis;
    private int generation;
    private String loggedNotices = "";

    private ConditionFiles(Path directory, String folder) {
        this.directory = directory;
        this.folder = folder;
    }

    public static Path root() {
        return Platform.getGameFolder().resolve("config").resolve(ConditionalVideos.MOD_ID);
    }

    public static synchronized ConditionFiles client() {
        if (client == null) {
            client = new ConditionFiles(root().resolve(CLIENT_FOLDER), CLIENT_FOLDER);
        }
        return client;
    }

    public static synchronized ConditionFiles server() {
        if (server == null) {
            server = new ConditionFiles(root().resolve(SERVER_FOLDER), SERVER_FOLDER);
        }
        return server;
    }

    // The folder a server reads: server-conditions/ when dedicated, the host's conditions/ otherwise.
    public static ConditionFiles active(boolean dedicatedServer) {
        return dedicatedServer ? server() : client();
    }

    public Path directory() {
        return directory;
    }

    public String folder() {
        return folder;
    }

    public synchronized ConditionalVideosConfig config() {
        refresh();
        return config;
    }

    public synchronized List<ConfigNotice> notices() {
        refresh();
        return notices;
    }

    // Grows every time the files are read again after a change.
    public synchronized int generation() {
        refresh();
        return generation;
    }

    public synchronized void invalidate() {
        nextCheckMillis = 0L;
    }

    // Creates custom/<id>.json as an empty condition for every id that has no file yet. Existing files are
    // never touched, even when broken. Returns the ids created.
    public synchronized List<String> seedCustom(Collection<String> ids) {
        nextCheckMillis = 0L;
        refresh();
        List<String> created = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return created;
        }
        Layout layout = new Layout();
        scanned.keySet().forEach(layout::claim);
        for (String id : ids) {
            if (owned.contains(ConditionRegistry.TYPE_CUSTOM + "/" + id)) {
                continue;
            }
            Layout.Placement placement = layout.place(ConditionRegistry.TYPE_CUSTOM, id);
            JsonObject content = new JsonObject();
            if (placement.needsId()) {
                content.addProperty("id", id);
            }
            try {
                writeNew(directory.resolve(placement.path()), content);
                created.add(id);
            } catch (IOException exception) {
                ConditionalVideos.LOGGER.warn("Could not create '{}/{}' for the custom condition '{}': {}", folder,
                        placement.path(), id, ConfigNotice.reason(exception));
            }
        }
        if (!created.isEmpty()) {
            nextCheckMillis = 0L;
        }
        return created;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now < nextCheckMillis) {
            return;
        }
        nextCheckMillis = now + RECHECK_MILLIS;
        TreeMap<String, String> current = scan();
        String currentFingerprint = current.toString();
        if (currentFingerprint.equals(fingerprint)) {
            return;
        }
        fingerprint = currentFingerprint;
        scanned = current;
        load(current, now);
    }

    // Every file and folder below the directory, hidden ones excluded, with size and modification time.
    private TreeMap<String, String> scan() {
        TreeMap<String, String> found = new TreeMap<>();
        if (!Files.isDirectory(directory)) {
            return found;
        }
        try {
            Files.walkFileTree(directory, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (dir.equals(directory)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (isHidden(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    found.put(relative(dir) + "/", "folder");
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (!isHidden(file) && attributes.isRegularFile()) {
                        found.put(relative(file), attributes.size() + "@" + attributes.lastModifiedTime().toMillis());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    if (!isHidden(file)) {
                        found.put(relative(file), "unreadable");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            found.put("", "unreadable");
        }
        return found;
    }

    // Reads every file into a fresh config. A broken file still owns the key its path gives, so neither a
    // second file with that key nor a seeded one is ever used beside it.
    private void load(TreeMap<String, String> entries, long startMillis) {
        ConditionalVideosConfig next = new ConditionalVideosConfig();
        List<ConfigNotice> found = new ArrayList<>();
        Map<String, String> owners = new HashMap<>();
        int files = 0;
        for (String relative : entries.keySet()) {
            int slash = relative.indexOf('/');
            String top = slash < 0 ? relative : relative.substring(0, slash);
            if (relative.endsWith("/")) {
                if (slash == relative.length() - 1 && !isType(top)) {
                    found.add(ConfigNotice.unknownFolder(folder + "/" + relative));
                }
                continue;
            }
            if (!relative.endsWith(".json") || (slash >= 0 && !isType(top))) {
                continue;
            }
            String label = folder + "/" + relative;
            String type;
            String key;
            JsonElement element;
            if (slash < 0) {
                if (relative.equals(UNRECOGNIZED_FILE)) {
                    continue;
                }
                type = relative.substring(0, relative.length() - 5);
                if (!SIMPLE_CONDITIONS.contains(type)) {
                    found.add(ConfigNotice.unknownFile(label));
                    continue;
                }
                files++;
                element = read(directory.resolve(relative), label, found);
                key = type;
            } else {
                type = top;
                files++;
                element = read(directory.resolve(relative), label, found);
                key = keyOf(type, relative.substring(slash + 1, relative.length() - 5), element, label, found);
            }
            if (key == null) {
                continue;
            }
            String id = slash < 0 ? key : type + "/" + key;
            String other = owners.putIfAbsent(id, label);
            if (other != null) {
                if (element != null) {
                    found.add(ConfigNotice.duplicate(label, other));
                }
                continue;
            }
            if (element != null) {
                next.accept(type, key, element, label, found);
            }
        }
        config = next;
        notices = List.copyOf(found);
        owned = Set.copyOf(owners.keySet());
        generation++;

        StringBuilder signature = new StringBuilder();
        notices.forEach(notice -> signature.append(notice.text()).append('\n'));
        if (!signature.toString().equals(loggedNotices)) {
            boolean hadNotices = !loggedNotices.isEmpty();
            loggedNotices = signature.toString();
            for (ConfigNotice notice : notices) {
                ConditionalVideos.LOGGER.warn("{}", notice.text());
            }
            if (notices.isEmpty() && hadNotices) {
                ConditionalVideos.LOGGER.info("The conditions in '{}' have no problems now.", folder);
            }
        }
        if (DebugLog.sideEnabled()) {
            DebugLog.side(DebugLog.Area.CONFIG, "Read '{}': {} file(s), {} playable condition(s), {} problem(s) ({} ms).",
                    directory, files, ConditionRegistry.listPlayableKeys(next).size(), notices.size(),
                    System.currentTimeMillis() - startMillis);
        }
    }

    // The condition key of a file inside a type folder: the "id" member when present, otherwise its path
    // (namespace/path.json -> namespace:path in the namespaced types, the plain path in the others). A
    // file that could not be read (null element) gets its path key without further notices.
    private static String keyOf(String type, String path, JsonElement element, String label, List<ConfigNotice> found) {
        JsonElement id = element != null && element.isJsonObject() ? element.getAsJsonObject().get("id") : null;
        if (id != null) {
            if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                return id.getAsString();
            }
            found.add(ConfigNotice.invalidId(label));
            return null;
        }
        if (FLAT_TYPES.contains(type)) {
            return path;
        }
        int slash = path.indexOf('/');
        if (slash < 0) {
            if (element != null) {
                found.add(ConfigNotice.missingNamespace(label));
            }
            return null;
        }
        return path.substring(0, slash) + ":" + path.substring(slash + 1);
    }

    private static JsonElement read(Path file, String label, List<ConfigNotice> found) {
        try {
            String text = readText(file);
            JsonElement element = text.isBlank() ? null : JsonParser.parseString(text);
            if (element == null || element.isJsonNull()) {
                found.add(ConfigNotice.notObject(label));
                return null;
            }
            return element;
        } catch (IOException | RuntimeException exception) {
            found.add(ConfigNotice.unreadable(label, exception));
            return null;
        }
    }

    // UTF-8 text without the byte order mark some editors add.
    public static String readText(Path file) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException exception) {
            throw new IOException("the file is not UTF-8 text");
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    // Writes a new file through a temporary sibling, so a crash never leaves it half written, and never
    // replaces a file that already exists.
    static void writeNew(Path target, JsonElement content) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            throw new IOException("'" + target.getFileName() + "' already exists");
        }
        Path temporary = target.resolveSibling("." + target.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, PRETTY.toJson(content) + "\n", StandardCharsets.UTF_8);
            Files.move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String relative(Path path) {
        return directory.relativize(path).toString().replace('\\', '/');
    }

    private static boolean isHidden(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    private static boolean isType(String name) {
        return NAMESPACED_TYPES.contains(name) || FLAT_TYPES.contains(name);
    }

    // Assigns condition keys their file paths inside a conditions folder. A key keeps its natural path
    // (type/namespace/path.json) unless it is unsafe on some system or clashes by letter case with
    // another path; then it gets a safe unique name and the file must carry the exact key in "id".
    static final class Layout {
        private static final int MAX_SEGMENT = 80;
        private static final String FORBIDDEN = "<>:\"\\|?*";
        private static final Set<String> RESERVED = new HashSet<>(Arrays.asList("con", "prn", "aux", "nul",
                "com0", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
                "lpt0", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"));

        private final Set<String> files = new HashSet<>();
        private final Map<String, String> folders = new HashMap<>();

        record Placement(String path, boolean needsId) {
        }

        // Records a path that exists or is planned; folders end with '/'.
        void claim(String path) {
            boolean isFolder = path.endsWith("/");
            String[] parts = path.split("/");
            int folderCount = isFolder ? parts.length : parts.length - 1;
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < folderCount; i++) {
                if (i > 0) {
                    prefix.append('/');
                }
                prefix.append(parts[i]);
                folders.putIfAbsent(prefix.toString().toLowerCase(Locale.ROOT), prefix.toString());
            }
            if (!isFolder) {
                files.add(path.toLowerCase(Locale.ROOT));
            }
        }

        boolean wouldNeedId(String type, String key) {
            List<String> natural = natural(type, key);
            return natural == null || !fits(natural);
        }

        Placement place(String type, String key) {
            List<String> natural = natural(type, key);
            if (natural != null && fits(natural)) {
                String path = String.join("/", natural) + ".json";
                claim(path);
                return new Placement(path, false);
            }
            List<String> safe = sanitized(type, key);
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < safe.size() - 1; i++) {
                String candidate = i == 0 ? safe.get(i) : prefix + "/" + safe.get(i);
                String existing = folders.get(candidate.toLowerCase(Locale.ROOT));
                if (existing != null) {
                    safe.set(i, existing.substring(existing.lastIndexOf('/') + 1));
                    candidate = existing;
                }
                prefix.setLength(0);
                prefix.append(candidate);
            }
            String base = String.join("/", safe);
            String path = base + ".json";
            for (int n = 2; files.contains(path.toLowerCase(Locale.ROOT)); n++) {
                path = base + "~" + n + ".json";
            }
            claim(path);
            return new Placement(path, true);
        }

        private boolean fits(List<String> segments) {
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segments.size() - 1; i++) {
                if (i > 0) {
                    prefix.append('/');
                }
                prefix.append(segments.get(i));
                String existing = folders.get(prefix.toString().toLowerCase(Locale.ROOT));
                if (existing != null && !existing.equals(prefix.toString())) {
                    return false;
                }
            }
            return !files.contains((String.join("/", segments) + ".json").toLowerCase(Locale.ROOT));
        }

        private static List<String> natural(String type, String key) {
            List<String> segments = new ArrayList<>();
            segments.add(type);
            String path = key;
            if (NAMESPACED_TYPES.contains(type)) {
                int colon = key.indexOf(':');
                if (colon < 0) {
                    return null;
                }
                segments.add(key.substring(0, colon));
                path = key.substring(colon + 1);
            }
            segments.addAll(Arrays.asList(path.split("/", -1)));
            for (int i = 1; i < segments.size(); i++) {
                if (!isSafe(segments.get(i))) {
                    return null;
                }
            }
            return segments;
        }

        private static List<String> sanitized(String type, String key) {
            List<String> segments = new ArrayList<>();
            segments.add(type);
            String path = key;
            if (NAMESPACED_TYPES.contains(type)) {
                int colon = key.indexOf(':');
                if (colon > 0) {
                    segments.add(clean(key.substring(0, colon)));
                    path = key.substring(colon + 1);
                }
            }
            for (String part : path.split("/", -1)) {
                segments.add(clean(part));
            }
            return segments;
        }

        // A file or folder name that works the same on Windows, macOS and Linux and is not hidden.
        private static boolean isSafe(String segment) {
            if (segment.isEmpty() || segment.length() > MAX_SEGMENT || segment.charAt(0) == '.'
                    || segment.endsWith(".") || segment.endsWith(" ")) {
                return false;
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c < 32 || c == 127 || FORBIDDEN.indexOf(c) >= 0) {
                    return false;
                }
            }
            int dot = segment.indexOf('.');
            return !RESERVED.contains((dot < 0 ? segment : segment.substring(0, dot)).toLowerCase(Locale.ROOT));
        }

        private static String clean(String part) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < part.length() && out.length() < MAX_SEGMENT - 1; i++) {
                char c = part.charAt(i);
                out.append(c < 32 || c == 127 || FORBIDDEN.indexOf(c) >= 0 ? '_' : c);
            }
            String result = out.toString();
            if (result.isEmpty()) {
                return "_";
            }
            if (result.charAt(0) == '.') {
                result = "_" + result.substring(1);
            }
            if (result.endsWith(".") || result.endsWith(" ")) {
                result = result.substring(0, result.length() - 1) + "_";
            }
            return isSafe(result) ? result : "_" + result;
        }
    }
}
