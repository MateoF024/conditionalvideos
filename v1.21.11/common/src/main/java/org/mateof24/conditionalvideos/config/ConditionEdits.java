package org.mateof24.conditionalvideos.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

// What the set command changes in a condition's file. Everything else in the file stays as written, and any
// problem refuses the change before the file is touched.
public final class ConditionEdits {
    private static final Pattern NON_NEGATIVE = Pattern.compile("\\d+(\\.\\d+)?");
    public static final String AT_POSITION = "at_position";

    public enum Property {
        VOLUME("volume", "volume <condition> <video_index> <volume>"),
        CONDITION_DELAY("conditiondelay", "conditiondelay <condition> <seconds>"),
        START_AT("startat", "startat <condition> <video_index> <seconds>"),
        TITLE("title", "title <condition> <video_index> [at_position <x> <y>] <text>"),
        DESCRIPTION("description", "description <condition> <video_index> [at_position <x> <y>] <text>"),
        TITLE_POSITION("titleposition", "titleposition <condition> <video_index> <position | x y>"),
        DESCRIPTION_POSITION("descriptionposition", "descriptionposition <condition> <video_index> <position | x y>");

        public final String literal;
        public final String usage;

        Property(String literal, String usage) {
            this.literal = literal;
            this.usage = usage;
        }

        public boolean hasVideo() {
            return this != CONDITION_DELAY;
        }
    }

    // A change that cannot be made, as a translation key and its arguments.
    public static final class Refused extends Exception {
        public final transient String key;
        public final transient Object[] args;

        public Refused(String key, Object... args) {
            super(key);
            this.key = key;
            this.args = args;
        }
    }

    private ConditionEdits() {
    }

    // The arguments are what follows the condition, with the video number first when the property has one.
    public static void apply(ConditionFiles files, Property property, String condition, String arguments) throws Refused {
        Tokens tokens = new Tokens(arguments);
        String videoToken = property.hasVideo() ? tokens.next() : null;
        if (property.hasVideo() && videoToken == null) {
            throw usage(property);
        }
        int video = property.hasVideo() ? parseVideo(videoToken) : 0;
        ConditionFiles.Edit edit = switch (property) {
            case VOLUME -> volume(tokens, property, condition, video);
            case CONDITION_DELAY -> conditionDelay(tokens, property);
            case START_AT -> startAt(tokens, property, condition, video);
            case TITLE -> text(tokens, property, condition, video, "videoTitle", "videoTitlePosition");
            case DESCRIPTION -> text(tokens, property, condition, video, "videoDescription", "videoDescriptionPosition");
            case TITLE_POSITION -> position(tokens, property, condition, video, "videoTitlePosition");
            case DESCRIPTION_POSITION -> position(tokens, property, condition, video, "videoDescriptionPosition");
        };
        try {
            if (!files.edit(ConditionRegistry.fileKey(condition), edit)) {
                throw new Refused("command.conditionalvideos.set.no_file", condition, files.folder());
            }
        } catch (IOException | RuntimeException exception) {
            throw new Refused("command.conditionalvideos.set.write_failed", ConfigNotice.reason(exception));
        }
    }

    // Puts names on the waiting room's list in firstJoin's file. Returns those that were not on it, in lowercase.
    public static Set<String> addRoomPlayers(ConditionFiles files, Collection<String> names) throws Refused {
        Set<String> changed = new HashSet<>();
        editRoomList(files, list -> {
            Set<String> written = writtenNames(list);
            for (String name : names) {
                if (written.add(name.toLowerCase(Locale.ROOT))) {
                    list.add(name);
                    changed.add(name.toLowerCase(Locale.ROOT));
                }
            }
        });
        return changed;
    }

    // Takes names off that list, whatever their case. Returns those that were on it, in lowercase.
    public static Set<String> removeRoomPlayers(ConditionFiles files, Collection<String> names) throws Refused {
        Set<String> wanted = new HashSet<>();
        names.forEach(name -> wanted.add(name.toLowerCase(Locale.ROOT)));
        Set<String> changed = new HashSet<>();
        editRoomList(files, list -> {
            for (int index = list.size() - 1; index >= 0; index--) {
                JsonElement entry = list.get(index);
                if (isText(entry) && wanted.contains(entry.getAsString().toLowerCase(Locale.ROOT))) {
                    changed.add(entry.getAsString().toLowerCase(Locale.ROOT));
                    list.remove(index);
                }
            }
        });
        return changed;
    }

    private static boolean isText(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static Set<String> writtenNames(JsonArray list) {
        Set<String> written = new HashSet<>();
        for (JsonElement entry : list) {
            if (isText(entry)) {
                written.add(entry.getAsString().toLowerCase(Locale.ROOT));
            }
        }
        return written;
    }

    private interface RoomListEdit {
        void apply(JsonArray list);
    }

    // Something that is not a list is the user's to fix: it is refused and the file stays as it is.
    private static void editRoomList(ConditionFiles files, RoomListEdit edit) throws Refused {
        String condition = ConditionRegistry.KEY_FIRST_JOIN;
        try {
            boolean found = files.edit(ConditionRegistry.fileKey(condition), object -> {
                JsonElement room = object.get("waitingRoom");
                if (room == null || !room.isJsonObject()) {
                    throw new Refused("command.conditionalvideos.waitingroom.invalid_list");
                }
                JsonElement list = room.getAsJsonObject().get("requiredPlayerNames");
                if (list == null) {
                    list = new JsonArray();
                    room.getAsJsonObject().add("requiredPlayerNames", list);
                }
                if (!list.isJsonArray()) {
                    throw new Refused("command.conditionalvideos.waitingroom.invalid_list");
                }
                edit.apply(list.getAsJsonArray());
            });
            if (!found) {
                throw new Refused("command.conditionalvideos.set.no_file", condition, files.folder());
            }
        } catch (IOException | RuntimeException exception) {
            throw new Refused("command.conditionalvideos.set.write_failed", ConfigNotice.reason(exception));
        }
    }

    private static ConditionFiles.Edit volume(Tokens tokens, Property property, String condition, int video) throws Refused {
        BigDecimal value = number(tokens, property, "videoVolume", "0-1");
        if (value.compareTo(BigDecimal.ONE) > 0) {
            throw new Refused("command.conditionalvideos.set.invalid_number", value, "videoVolume", "0-1");
        }
        return object -> video(object, condition, video).addProperty("videoVolume", value);
    }

    private static ConditionFiles.Edit conditionDelay(Tokens tokens, Property property) throws Refused {
        BigDecimal value = number(tokens, property, "conditionDelay", ">= 0");
        return object -> object.addProperty("conditionDelay", value);
    }

    private static ConditionFiles.Edit startAt(Tokens tokens, Property property, String condition, int video) throws Refused {
        BigDecimal value = number(tokens, property, "startAt", ">= 0");
        return object -> {
            JsonObject entry = video(object, condition, video);
            JsonElement nextAt = entry.get("nextAt");
            if (nextAt != null && nextAt.isJsonPrimitive() && nextAt.getAsJsonPrimitive().isNumber()
                    && nextAt.getAsDouble() > 0d && value.doubleValue() >= nextAt.getAsDouble()) {
                throw new Refused("command.conditionalvideos.set.start_after_next", value, nextAt.getAsString());
            }
            entry.addProperty("startAt", value);
        };
    }

    // A text without a position keeps the one it had; the default one when it had none.
    private static ConditionFiles.Edit text(Tokens tokens, Property property, String condition, int video, String textField, String positionField) throws Refused {
        String position = null;
        int start = tokens.position();
        if (AT_POSITION.equals(tokens.next())) {
            String x = tokens.next();
            String y = tokens.next();
            if (x != null && y != null && exactPosition(x, y)) {
                position = x + "/" + y;
                start = tokens.position();
            }
        }
        tokens.moveTo(start);
        String raw = tokens.rest();
        if (raw.isEmpty()) {
            throw usage(property);
        }
        String value = unescape(raw);
        String newPosition = position;
        return object -> {
            JsonObject entry = video(object, condition, video);
            entry.addProperty(textField, value);
            if (newPosition != null) {
                entry.addProperty(positionField, newPosition);
            }
        };
    }

    private static ConditionFiles.Edit position(Tokens tokens, Property property, String condition, int video, String field) throws Refused {
        String first = tokens.next();
        String second = tokens.next();
        if (first == null || tokens.next() != null) {
            throw usage(property);
        }
        String value = second == null ? first : first + "/" + second;
        if (TextPosition.parse(value) == null) {
            throw new Refused("command.conditionalvideos.set.invalid_position", value);
        }
        return object -> video(object, condition, video).addProperty(field, value);
    }

    private static boolean exactPosition(String x, String y) {
        return TextPosition.parse(x + "/" + y) != null;
    }

    private static int parseVideo(String token) throws Refused {
        try {
            int video = Integer.parseInt(token);
            if (video >= 1) {
                return video;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new Refused("command.conditionalvideos.set.invalid_number", token, "video", ">= 1");
    }

    private static BigDecimal number(Tokens tokens, Property property, String field, String range) throws Refused {
        String token = tokens.next();
        if (token == null || tokens.next() != null) {
            throw usage(property);
        }
        if (!NON_NEGATIVE.matcher(token).matches()) {
            throw new Refused("command.conditionalvideos.set.invalid_number", token, field, range);
        }
        return new BigDecimal(token);
    }

    // The number a video has in its file, starting at 1, the same the problem notices use.
    private static JsonObject video(JsonObject condition, String key, int number) throws Refused {
        JsonElement videos = condition.get("videos");
        int size = videos != null && videos.isJsonArray() ? videos.getAsJsonArray().size() : 0;
        if (number > size) {
            throw new Refused("command.conditionalvideos.set.invalid_video", number, key, size);
        }
        JsonElement entry = videos.getAsJsonArray().get(number - 1);
        if (!entry.isJsonObject()) {
            throw new Refused("command.conditionalvideos.set.invalid_video", number, key, size);
        }
        return entry.getAsJsonObject();
    }

    // What typing does not give by itself: \n is a line break and \\n shows those characters, as in the JSON.
    public static String unescape(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (current == '\\' && next == '\\') {
                result.append('\\');
                i++;
            } else if (current == '\\' && next == 'n') {
                result.append('\n');
                i++;
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static Refused usage(Property property) {
        return new Refused("command.conditionalvideos.set.usage", "/conditionalvideos set " + property.usage);
    }

    private static final class Tokens {
        private final String text;
        private int position;

        Tokens(String text) {
            this.text = text;
        }

        String next() {
            skipSpaces();
            if (position >= text.length()) {
                return null;
            }
            int start = position;
            while (position < text.length() && !Character.isWhitespace(text.charAt(position))) {
                position++;
            }
            return text.substring(start, position);
        }

        String rest() {
            skipSpaces();
            return text.substring(position).stripTrailing();
        }

        int position() {
            return position;
        }

        void moveTo(int value) {
            position = value;
        }

        private void skipSpaces() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }
    }
}
