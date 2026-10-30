package org.mateof24.conditionalvideos.condition.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.api.PlayOptions;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionFiles;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.world.ConditionalVideosWorldData;
import org.mateof24.conditionalvideos.world.PlaybackSessions;
import org.mateof24.conditionalvideos.world.PlaybackSync;
import org.mateof24.conditionalvideos.world.PlayerRoles;
import org.mateof24.conditionalvideos.world.WatchedConditions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ServerConditionDispatcher {
    // Conditions of each player waiting for their conditionDelay, with the ticks left.
    private static final Map<UUID, Map<String, PendingDelay>> DELAYED = new HashMap<>();
    private static ConditionalVideosConfig loggedConfig;

    private ServerConditionDispatcher() {
    }

    public enum Outcome {
        SENT, WAITING, UNKNOWN, NO_VIDEOS, EXCLUDED, WATCHED, BUSY;

        public boolean started() {
            return this == SENT || this == WAITING;
        }
    }

    private static final class PendingDelay {
        private final PlayOptions options;
        private int ticksLeft;

        private PendingDelay(int ticksLeft, PlayOptions options) {
            this.ticksLeft = ticksLeft;
            this.options = options;
        }
    }

    // Game events without a configured condition stay silent; only configured keys log their decision.
    public static boolean fire(ServerPlayer player, String conditionKey) {
        return play(player, conditionKey, PlayOptions.FILE).started();
    }

    // Follows the condition's file; the options change it for this one playback.
    public static Outcome play(ServerPlayer player, String conditionKey, PlayOptions options) {
        if (player == null) {
            return Outcome.UNKNOWN;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Outcome.UNKNOWN;
        }
        ConditionConfig conditionConfig = ConditionRegistry.resolve(activeConfig(server), conditionKey);
        if (conditionConfig == null) {
            return Outcome.UNKNOWN;
        }
        String excluded = options.exclusionReason(conditionConfig, PlayerRoles.viewer(player));
        if (excluded != null) {
            DebugLog.server(DebugLog.Area.CONDITION, "'{}' does not fire for {}: {}.", conditionKey, player.getScoreboardName(), excluded);
            return Outcome.EXCLUDED;
        }
        if (conditionConfig.resolvedPlaylist().isEmpty()) {
            DebugLog.server(DebugLog.Area.CONDITION, "'{}' for {} is configured without videos; nothing to send.",
                    conditionKey, player.getScoreboardName());
            return Outcome.NO_VIDEOS;
        }
        // Joining someone already watching has nothing to wait for.
        int delayTicks = options.sync() && PlaybackSessions.otherWatcher(server, conditionKey, player.getUUID()) != null ? 0 : options.delayTicks(conditionConfig);
        Map<String, PendingDelay> delayed = DELAYED.get(player.getUUID());
        if (delayTicks > 0 && delayed != null && delayed.containsKey(conditionKey)) {
            DebugLog.server(DebugLog.Area.SCHEDULE, "'{}' for {} is already waiting for its delay; ignoring the repeat.", conditionKey, player.getScoreboardName());
            return Outcome.BUSY;
        }
        if (options.stopsIfWatched(conditionConfig)) {
            if (ConditionalVideosWorldData.get(server).inProgress(player.getUUID()).contains(conditionKey)) {
                DebugLog.server(DebugLog.Area.CONDITION, "'{}' is already in progress for {}; not sending it again.",
                        conditionKey, player.getScoreboardName());
                return Outcome.BUSY;
            }
            if (WatchedConditions.isWatched(player, conditionKey)) {
                DebugLog.server(DebugLog.Area.CONDITION, "'{}' was already watched by {} in this world; not sending it.",
                        conditionKey, player.getScoreboardName());
                return Outcome.WATCHED;
            }
            if (!WatchedConditions.order(player, conditionKey)) {
                DebugLog.server(DebugLog.Area.CONDITION, "'{}' already fired for {} during this connection; not sending it again.",
                        conditionKey, player.getScoreboardName());
                return Outcome.BUSY;
            }
            if (options.once() && conditionConfig.repeatableInSameSession()) {
                WatchedConditions.countOnce(player, conditionKey);
            }
        }
        WatchedConditions.order(player, conditionKey);
        if (delayTicks > 0) {
            DELAYED.computeIfAbsent(player.getUUID(), id -> new HashMap<>()).put(conditionKey, new PendingDelay(delayTicks, options));
            PlaybackSessions.markInProgress(player, conditionKey);
            DebugLog.server(DebugLog.Area.SCHEDULE, "'{}' fired for {}; it plays in {} ticks.", conditionKey, player.getScoreboardName(), delayTicks);
            return Outcome.WAITING;
        }
        DebugLog.server(DebugLog.Area.CONDITION, "'{}' fired for {}.", conditionKey, player.getScoreboardName());
        send(server, player, conditionKey, options);
        return Outcome.SENT;
    }

    private static void send(MinecraftServer server, ServerPlayer player, String conditionKey, PlayOptions options) {
        if (options.sync()) {
            PlaybackSync.send(server, player, conditionKey);
        } else {
            PlaybackControlNetworking.sendPlay(player, conditionKey);
        }
    }

    // A command does not stack on what a player still has going; a natural trigger queues instead.
    public static boolean isBusy(ServerPlayer player, String conditionKey) {
        Map<String, PendingDelay> delayed = DELAYED.get(player.getUUID());
        return (delayed != null && delayed.containsKey(conditionKey)) || PlaybackSessions.isBusy(player) || PlaybackSync.isPending(player);
    }

    // Sends the orders whose conditionDelay ran out. From here the client's reports take over.
    public static void tickDelayed(MinecraftServer server) {
        if (DELAYED.isEmpty()) {
            return;
        }
        for (UUID id : List.copyOf(DELAYED.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            Map<String, PendingDelay> delayed = DELAYED.get(id);
            for (String key : List.copyOf(delayed.keySet())) {
                PendingDelay pending = delayed.get(key);
                if (--pending.ticksLeft > 0) {
                    continue;
                }
                delayed.remove(key);
                if (player != null) {
                    DebugLog.server(DebugLog.Area.SCHEDULE, "The delay of '{}' for {} ran out.", key, player.getScoreboardName());
                    PlaybackSessions.clearInProgress(player, key);
                    ConditionConfig condition = ConditionRegistry.resolve(activeConfig(server), key);
                    if (condition != null && pending.options.exclusionReason(condition, PlayerRoles.viewer(player)) == null) {
                        send(server, player, key, pending.options);
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
        Map<String, PendingDelay> delayed = DELAYED.remove(player.getUUID());
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
