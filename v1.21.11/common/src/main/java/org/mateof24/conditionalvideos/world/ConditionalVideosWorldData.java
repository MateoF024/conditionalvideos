package org.mateof24.conditionalvideos.world;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.mateof24.conditionalvideos.ConditionalVideos;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// ConditionalVideos' data inside each world (<world>/data/conditionalvideos.dat), written only by the
// server: the conditions every player already watched there, and whether the singleplayer history of
// 1.5.1 was already brought in.
public final class ConditionalVideosWorldData extends SavedData {
    private static final Codec<ConditionalVideosWorldData> CODEC =
            CompoundTag.CODEC.xmap(ConditionalVideosWorldData::load, ConditionalVideosWorldData::save);
    private static final SavedDataType<ConditionalVideosWorldData> TYPE =
            new SavedDataType<>(ConditionalVideos.MOD_ID, ConditionalVideosWorldData::new, CODEC, null);

    private final Map<String, PlayerRecord> players = new LinkedHashMap<>();
    private boolean legacyImported;

    public static ConditionalVideosWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
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

    private CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag playersTag = new CompoundTag();
        players.forEach((uuid, record) -> {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putString("name", record.name);
            ListTag watched = new ListTag();
            record.watched.forEach(key -> watched.add(StringTag.valueOf(key)));
            playerTag.put("watched", watched);
            playersTag.put(uuid, playerTag);
        });
        tag.put("players", playersTag);
        tag.putBoolean("legacyImported", legacyImported);
        return tag;
    }

    private static ConditionalVideosWorldData load(CompoundTag tag) {
        ConditionalVideosWorldData data = new ConditionalVideosWorldData();
        CompoundTag playersTag = tag.getCompoundOrEmpty("players");
        for (String uuid : playersTag.keySet()) {
            CompoundTag playerTag = playersTag.getCompoundOrEmpty(uuid);
            PlayerRecord record = new PlayerRecord();
            record.name = playerTag.getStringOr("name", "");
            ListTag watched = playerTag.getListOrEmpty("watched");
            for (int i = 0; i < watched.size(); i++) {
                record.watched.add(watched.getStringOr(i, ""));
            }
            data.players.put(uuid, record);
        }
        data.legacyImported = tag.getBooleanOr("legacyImported", false);
        return data;
    }

    private static final class PlayerRecord {
        private String name = "";
        private final Set<String> watched = new LinkedHashSet<>();
    }
}
