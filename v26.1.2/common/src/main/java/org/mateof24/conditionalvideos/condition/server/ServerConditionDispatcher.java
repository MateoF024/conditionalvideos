package org.mateof24.conditionalvideos.condition.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionFiles;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ServerConditionDispatcher {
    private static final Map<UUID, Set<String>> firedConditions = new HashMap<>();
    private static ConditionalVideosConfig loggedConfig;

    private ServerConditionDispatcher() {
    }

    // Game events without a configured condition stay silent; only configured keys log their decision.
    public static boolean fire(ServerPlayer player, String conditionKey) {
        if (player == null) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
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
            Set<String> fired = firedConditions.computeIfAbsent(player.getUUID(), key -> new HashSet<>());
            if (!fired.add(conditionKey)) {
                DebugLog.server(DebugLog.Area.CONDITION, "'{}' already fired for {} during this connection; not sending it again.",
                        conditionKey, player.getScoreboardName());
                return false;
            }
        }
        DebugLog.server(DebugLog.Area.CONDITION, "'{}' fired for {}.", conditionKey, player.getScoreboardName());
        PlaybackControlNetworking.sendPlay(player, conditionKey);
        return true;
    }

    public static void clearPlayer(ServerPlayer player) {
        firedConditions.remove(player.getUUID());
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
