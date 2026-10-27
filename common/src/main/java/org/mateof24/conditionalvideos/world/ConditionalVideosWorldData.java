package org.mateof24.conditionalvideos.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.mateof24.conditionalvideos.ConditionalVideos;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// ConditionalVideos' data inside each world (<world>/data/conditionalvideos.dat), written only by the
// server: the conditions every player already watched there, the protection a player is under (so it
// can be undone after a disconnect or a crash), and whether the singleplayer history of 1.5.1 was
// already brought in.
public final class ConditionalVideosWorldData extends SavedData {
    // What a protection changed: the condition, and the invulnerability and game mode before it began.
    public record Protection(String key, boolean invulnerable, String gameMode) {
    }

    private final Map<String, PlayerRecord> players = new LinkedHashMap<>();
    private boolean legacyImported;

    public static ConditionalVideosWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ConditionalVideosWorldData::load,
                ConditionalVideosWorldData::new, ConditionalVideos.MOD_ID);
    }

    public Set<String> watched(UUID player) {
        PlayerRecord record = players.get(player.toString());
        return record == null ? Set.of() : Collections.unmodifiableSet(record.watched);
    }

    // Adds conditions to a player's watched list, keeping their current name beside the UUID.
    // Returns whether any condition was new.
    public boolean markWatched(UUID player, String name, Collection<String> keys) {
        PlayerRecord record = players.computeIfAbsent(player.toString(), id -> new PlayerRecord());
        boolean renamed = !name.equals(record.name);
        record.name = name;
        boolean added = record.watched.addAll(keys);
        if (renamed || added) {
            setDirty();
        }
        return added;
    }

    public Protection protection(UUID player) {
        PlayerRecord record = players.get(player.toString());
        return record == null ? null : record.protection;
    }

    // Stores the protection a player is under, or clears it with null.
    public void setProtection(UUID player, String name, Protection protection) {
        PlayerRecord record = players.computeIfAbsent(player.toString(), id -> new PlayerRecord());
        record.name = name;
        record.protection = protection;
        setDirty();
    }

    public int playerCount() {
        return players.size();
    }

    public boolean legacyImported() {
        return legacyImported;
    }

    public void setLegacyImported() {
        legacyImported = true;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag playersTag = new CompoundTag();
        players.forEach((uuid, record) -> {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putString("name", record.name);
            ListTag watched = new ListTag();
            record.watched.forEach(key -> watched.add(StringTag.valueOf(key)));
            playerTag.put("watched", watched);
            if (record.protection != null) {
                CompoundTag protectionTag = new CompoundTag();
                protectionTag.putString("key", record.protection.key());
                protectionTag.putBoolean("invulnerable", record.protection.invulnerable());
                protectionTag.putString("gameMode", record.protection.gameMode());
                playerTag.put("protection", protectionTag);
            }
            playersTag.put(uuid, playerTag);
        });
        tag.put("players", playersTag);
        tag.putBoolean("legacyImported", legacyImported);
        return tag;
    }

    private static ConditionalVideosWorldData load(CompoundTag tag) {
        ConditionalVideosWorldData data = new ConditionalVideosWorldData();
        CompoundTag playersTag = tag.getCompound("players");
        for (String uuid : playersTag.getAllKeys()) {
            CompoundTag playerTag = playersTag.getCompound(uuid);
            PlayerRecord record = new PlayerRecord();
            record.name = playerTag.getString("name");
            ListTag watched = playerTag.getList("watched", Tag.TAG_STRING);
            for (int i = 0; i < watched.size(); i++) {
                record.watched.add(watched.getString(i));
            }
            if (playerTag.contains("protection")) {
                CompoundTag protectionTag = playerTag.getCompound("protection");
                record.protection = new Protection(protectionTag.getString("key"),
                        protectionTag.getBoolean("invulnerable"), protectionTag.getString("gameMode"));
            }
            data.players.put(uuid, record);
        }
        data.legacyImported = tag.getBoolean("legacyImported");
        return data;
    }

    private static final class PlayerRecord {
        private String name = "";
        private final Set<String> watched = new LinkedHashSet<>();
        private Protection protection;
    }
}
