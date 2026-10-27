package org.mateof24.conditionalvideos.condition.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionFiles;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.world.WatchedConditions;

import java.util.List;

public final class ServerConditionDispatcher {
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
        if (conditionConfig.resolvedPlaylist().isEmpty()) {
            DebugLog.server(DebugLog.Area.CONDITION, "'{}' for {} is configured without videos; nothing to send.",
                    conditionKey, player.getScoreboardName());
            return false;
        }
        if (!conditionConfig.repeatableInSameSession()) {
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
        DebugLog.server(DebugLog.Area.CONDITION, "'{}' fired for {}.", conditionKey, player.getScoreboardName());
        PlaybackControlNetworking.sendPlay(player, conditionKey);
        return true;
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
