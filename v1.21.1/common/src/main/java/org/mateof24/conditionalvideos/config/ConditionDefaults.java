package org.mateof24.conditionalvideos.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

// Every option of a condition, so a file can show them all at its head. A value already written is never changed:
// only what is missing gets its default. Videos keep to themselves: nothing is written inside them.
final class ConditionDefaults {
    private ConditionDefaults() {
    }

    // Once per world for firstJoin; every other condition repeats unless its file says otherwise.
    static boolean repeatable(String type) {
        return !"firstJoin".equals(type);
    }

    // What absent means in a file of this type. Without a meaningful default, an option shows as null and stays an
    // error until it is filled in.
    private static Map<String, JsonElement> of(String type) {
        Map<String, JsonElement> options = new LinkedHashMap<>();
        options.put("repeatableInSameSession", new JsonPrimitive(repeatable(type)));
        options.put("playlistLoop", new JsonPrimitive(false));
        if ("scoreboard".equals(type)) {
            options.put("value", new JsonPrimitive(0));
            options.put("comparator", new JsonPrimitive(ConditionalVideosConfig.COMPARATOR_GREATER_OR_EQUAL));
        }
        if ("worldPosition".equals(type)) {
            options.put("dimension", JsonNull.INSTANCE);
            options.put("from", JsonNull.INSTANCE);
            options.put("to", JsonNull.INSTANCE);
            options.put("trigger", new JsonPrimitive(ConditionalVideosConfig.TRIGGER_ENTER));
        }
        options.put("conditionDelay", new JsonPrimitive(0.0));
        options.put("replayOnReconnect", new JsonPrimitive(false));
        options.put("disableLoadingScreen", new JsonPrimitive(false));
        options.put("protectPlayer", new JsonPrimitive(true));
        options.put("freezeWorld", new JsonPrimitive("firstJoin".equals(type)));
        options.put("excludePlayers", new JsonArray());
        options.put("excludeTeams", new JsonArray());
        options.put("excludeOps", new JsonPrimitive(false));
        return options;
    }

    private static JsonObject room() {
        JsonObject room = new JsonObject();
        room.addProperty("enabled", false);
        room.addProperty("requiredPlayers", 0);
        room.add("requiredPlayerNames", new JsonArray());
        room.add("requiredTeams", new JsonArray());
        room.addProperty("allowLeave", false);
        room.addProperty("allowChat", false);
        room.addProperty("startMode", ConditionalVideosConfig.ROOM_START_AUTO);
        room.addProperty("background", "#000000");
        room.add("title", JsonNull.INSTANCE);
        room.addProperty("titlePosition", ConditionalVideosConfig.ROOM_DEFAULT_POSITION);
        room.addProperty("titleAlignment", ConditionalVideosConfig.ROOM_DEFAULT_ALIGNMENT);
        room.addProperty("titleScale", 1.0);
        room.add("description", JsonNull.INSTANCE);
        room.addProperty("descriptionPosition", ConditionalVideosConfig.ROOM_DEFAULT_POSITION);
        room.addProperty("descriptionAlignment", ConditionalVideosConfig.ROOM_DEFAULT_ALIGNMENT);
        room.addProperty("descriptionScale", 1.0);
        room.addProperty("counter", WaitingRoomText.DEFAULT_COUNTER);
        room.addProperty("counterPosition", ConditionalVideosConfig.ROOM_DEFAULT_POSITION);
        room.addProperty("counterAlignment", ConditionalVideosConfig.ROOM_DEFAULT_ALIGNMENT);
        room.addProperty("counterScale", 1.0);
        return room;
    }

    // A waitingRoom already written keeps its values and gains only what it lacks, in the same order as the rest.
    private static JsonElement completedRoom(JsonObject file) {
        JsonObject defaults = room();
        JsonElement written = file.get("waitingRoom");
        if (written == null) {
            return defaults;
        }
        if (!written.isJsonObject() || written.getAsJsonObject().keySet().containsAll(defaults.keySet())) {
            return written;
        }
        JsonObject completed = new JsonObject();
        defaults.entrySet().forEach(option -> completed.add(option.getKey(), written.getAsJsonObject().has(option.getKey()) ? written.getAsJsonObject().get(option.getKey()) : option.getValue()));
        written.getAsJsonObject().entrySet().forEach(member -> {
            if (!completed.has(member.getKey())) {
                completed.add(member.getKey(), member.getValue());
            }
        });
        return completed;
    }

    // The same object when nothing is missing, so a complete file is left exactly as it is. Otherwise the options
    // go first, each with what the file had or its default, and then the rest in its own order. A 1.1.x file has its
    // video in "video", and an empty "videos" beside it would hide it.
    static JsonObject complete(String type, JsonObject file) {
        Map<String, JsonElement> options = of(type);
        boolean noVideos = !file.has("videos") && !file.has("video");
        JsonElement room = "firstJoin".equals(type) ? completedRoom(file) : null;
        boolean roomMissing = room != null && room != file.get("waitingRoom");
        if (!noVideos && !roomMissing && file.keySet().containsAll(options.keySet())) {
            return file;
        }
        JsonObject completed = new JsonObject();
        if (file.has("id")) {
            completed.add("id", file.get("id"));
        }
        options.forEach((name, value) -> completed.add(name, file.has(name) ? file.get(name) : value));
        if (room != null) {
            completed.add("waitingRoom", room);
        }
        for (Map.Entry<String, JsonElement> member : file.entrySet()) {
            if (!completed.has(member.getKey())) {
                completed.add(member.getKey(), member.getValue());
            }
        }
        if (noVideos) {
            completed.add("videos", new JsonArray());
        }
        return completed;
    }
}
