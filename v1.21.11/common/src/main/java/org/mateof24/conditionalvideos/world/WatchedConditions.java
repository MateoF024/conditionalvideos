package org.mateof24.conditionalvideos.world;

import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Server side of "already watched": a condition that is not repeatable plays once per player and world,
// on every kind of server. Each player gets their list on join and their client reports every playback
// it starts and finishes; a start is recorded when it came from a natural trigger, never from a forced
// play (command or API).
public final class WatchedConditions {
    // Natural orders for non-repeatable conditions sent during each connection, until their start arrives.
    private static final Map<UUID, Set<String>> ORDERED = new HashMap<>();

    private WatchedConditions() {
    }

    // The player's list for this world. The singleplayer host first gets the history of 1.5.1, once per world.
    public static List<String> listFor(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        ConditionalVideosWorldData data = ConditionalVideosWorldData.get(server);
        if (!data.legacyImported() && PlayerRoles.isHost(server, player)) {
            importLegacy(server, player, data);
        }
        return List.copyOf(data.watched(player.getUUID()));
    }

    public static boolean isWatched(ServerPlayer player, String key) {
        return ConditionalVideosWorldData.get(player.level().getServer()).watched(player.getUUID()).contains(key);
    }

    // Remembers a natural order; false when the same condition was already ordered during this connection.
    public static boolean order(ServerPlayer player, String key) {
        return ORDERED.computeIfAbsent(player.getUUID(), id -> new HashSet<>()).add(key);
    }

    public static void onPlayback(ServerPlayer player, String key, boolean started, boolean once) {
        if (!started) {
            DebugLog.server(DebugLog.Area.WORLD, "{} finished '{}'.", player.getScoreboardName(), key);
            return;
        }
        Set<String> ordered = ORDERED.get(player.getUUID());
        boolean wasOrdered = ordered != null && ordered.remove(key);
        MinecraftServer server = player.level().getServer();
        ConditionConfig condition = ConditionRegistry.resolve(ServerConditionDispatcher.activeConfig(server), key);
        if (condition == null) {
            DebugLog.server(DebugLog.Area.WORLD, "{} started '{}', which is not a condition of this server; not recorded.",
                    player.getScoreboardName(), key);
            return;
        }
        if (condition.repeatableInSameSession() || !(once || wasOrdered)) {
            DebugLog.server(DebugLog.Area.WORLD, "{} started '{}' ({}).", player.getScoreboardName(), key,
                    condition.repeatableInSameSession() ? "repeatable" : "forced play, not counted as watched");
            return;
        }
        ConditionalVideosWorldData.get(server).markWatched(player.getUUID(), player.getScoreboardName(), List.of(key));
        DebugLog.server(DebugLog.Area.WORLD, "{} started '{}'; it will not play again for them in this world.",
                player.getScoreboardName(), key);
    }

    public static void clearPlayer(ServerPlayer player) {
        ORDERED.remove(player.getUUID());
    }

    private static void importLegacy(MinecraftServer server, ServerPlayer host, ConditionalVideosWorldData data) {
        String levelName = server.getWorldData().getLevelName();
        LegacyWatchedImport.Result result = LegacyWatchedImport.collect(Platform.getGameFolder().resolve("config"), levelName);
        data.markWatched(host.getUUID(), host.getScoreboardName(), result.keys());
        data.setLegacyImported();
        if (result.keys().isEmpty()) {
            DebugLog.server(DebugLog.Area.WORLD, "No conditions watched in 1.5.1 were found for world '{}'.", levelName);
            return;
        }
        ConditionalVideos.LOGGER.info("Imported {} condition(s) already watched in 1.5.1 into world '{}' for {}.",
                result.keys().size(), levelName, host.getScoreboardName());
        DebugLog.server(DebugLog.Area.WORLD, "1.5.1 import for world '{}' from {}: {}.", levelName, result.sources(), result.keys());
    }
}
