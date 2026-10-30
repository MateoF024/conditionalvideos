package org.mateof24.conditionalvideos.condition.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionFiles;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.world.ConditionalVideosWorldData;
import org.mateof24.conditionalvideos.world.PlaybackSessions;
import org.mateof24.conditionalvideos.world.PlayerExclusions;
import org.mateof24.conditionalvideos.world.WatchedConditions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ServerConditionDispatcher {
    // Conditions of each player waiting for their conditionDelay, with the ticks left.
    private static final Map<UUID, Map<String, Integer>> DELAYED = new HashMap<>();
    private static ConditionalVideosConfig loggedConfig;

    private ServerConditionDispatcher() {
    }

    // Game events without a configured condition stay silent; only configured keys log their decision.
    public static boolean fire(ServerPlayer player, String conditionKey) {
        if (player == null) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        ConditionConfig conditionConfig = ConditionRegistry.resolve(activeConfig(server), conditionKey);
        if (conditionConfig == null) {
            return false;
        }
        String excluded = PlayerExclusions.reason(player, conditionConfig);
        if (excluded != null) {
            DebugLog.server(DebugLog.Area.CONDITION, "'{}' does not fire for {}: {}.", conditionKey, player.getScoreboardName(), excluded);
            return false;
        }
        if (conditionConfig.resolvedPlaylist().isEmpty()) {
            DebugLog.server(DebugLog.Area.CONDITION, "'{}' for {} is configured without videos; nothing to send.",
                    conditionKey, player.getScoreboardName());
            return false;
        }
        Map<String, Integer> delayed = DELAYED.get(player.getUUID());
        if (delayed != null && delayed.containsKey(conditionKey)) {
            DebugLog.server(DebugLog.Area.SCHEDULE, "'{}' for {} is already waiting for its delay; ignoring the repeat.", conditionKey, player.getScoreboardName());
            return false;
        }
        if (!conditionConfig.repeatableInSameSession()) {
            if (ConditionalVideosWorldData.get(server).inProgress(player.getUUID()).contains(conditionKey)) {
                DebugLog.server(DebugLog.Area.CONDITION, "'{}' is already in progress for {}; not sending it again.",
                        conditionKey, player.getScoreboardName());
                return false;
            }
            if (WatchedConditions.isWatched(player, conditionKey)) {
                DebugLog.server(DebugLog.Area.CONDITION, "'{}' was already watched by {} in this world; not sending it.",
                        conditionKey, player.getScoreboardName());
                return false;
            }
            if (!WatchedConditions.order(player, conditionKey)) {
                DebugLog.server(DebugLog.Area.CONDITION, "'{}' already fired for {} during this connection; not sending it again.",
                        conditionKey, player.getScoreboardName());
                return false;
            }
        }
        WatchedConditions.order(player, conditionKey);
        int delayTicks = conditionConfig.resolvedDelayTicks();
        if (delayTicks > 0) {
            DELAYED.computeIfAbsent(player.getUUID(), id -> new HashMap<>()).put(conditionKey, delayTicks);
            PlaybackSessions.markInProgress(player, conditionKey);
            DebugLog.server(DebugLog.Area.SCHEDULE, "'{}' fired for {}; it plays in {} ticks.", conditionKey, player.getScoreboardName(), delayTicks);
            return true;
        }
        DebugLog.server(DebugLog.Area.CONDITION, "'{}' fired for {}.", conditionKey, player.getScoreboardName());
        PlaybackControlNetworking.sendPlay(player, conditionKey);
        return true;
    }

    // Sends the orders whose conditionDelay ran out. From here the client's reports take over.
    public static void tickDelayed(MinecraftServer server) {
        if (DELAYED.isEmpty()) {
            return;
        }
        for (UUID id : List.copyOf(DELAYED.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            Map<String, Integer> delayed = DELAYED.get(id);
            for (String key : List.copyOf(delayed.keySet())) {
                int left = delayed.get(key) - 1;
                if (left > 0) {
                    delayed.put(key, left);
                    continue;
                }
                delayed.remove(key);
                if (player != null) {
                    DebugLog.server(DebugLog.Area.SCHEDULE, "The delay of '{}' for {} ran out.", key, player.getScoreboardName());
                    PlaybackSessions.clearInProgress(player, key);
                    if (PlayerExclusions.reason(player, ConditionRegistry.resolve(activeConfig(server), key)) == null) {
                        PlaybackControlNetworking.sendPlay(player, key);
                    }
                }
            }
            if (delayed.isEmpty()) {
                DELAYED.remove(id);
            }
        }
    }

    // A stop order also cancels what is still waiting for that player.
    public static void cancelDelayed(ServerPlayer player) {
        Map<String, Integer> delayed = DELAYED.remove(player.getUUID());
        if (delayed != null) {
            delayed.keySet().forEach(key -> PlaybackSessions.clearInProgress(player, key));
            DebugLog.server(DebugLog.Area.SCHEDULE, "Cancelled {} delayed condition(s) of {}.", delayed.size(), player.getScoreboardName());
        }
    }

    // On disconnect the world data keeps the note; the delay itself goes with the connection.
    public static void clearPlayer(ServerPlayer player) {
        DELAYED.remove(player.getUUID());
    }

    public static void clearDelayed() {
        DELAYED.clear();
    }

    public static int delayedCount() {
        return DELAYED.values().stream().mapToInt(Map::size).sum();
    }

    public static ConditionalVideosConfig activeConfig(MinecraftServer server) {
        ConditionalVideosConfig config = ConditionFiles.active(server.isDedicatedServer()).config();
        if (config != loggedConfig && DebugLog.serverEnabled()) {
            List<String> playable = ConditionRegistry.listPlayableKeys(config);
            DebugLog.server(DebugLog.Area.CONFIG, "Active conditions: {} playable {}.", playable.size(), playable);
        }
        loggedConfig = config;
        return config;
    }
}
