package org.mateof24.conditionalvideos.condition.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ServerConditionDispatcher {
    private static final long CACHE_TTL_MILLIS = 5000L;

    private static final Map<UUID, Set<String>> firedConditions = new HashMap<>();
    private static ConditionalVideosConfig cachedConfig;
    private static long cacheExpiryMillis;

    private ServerConditionDispatcher() {
    }

    public static boolean fire(ServerPlayer player, String conditionKey) {
        if (player == null) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        ConditionConfig conditionConfig = ConditionRegistry.resolve(activeConfig(server), conditionKey);
        if (conditionConfig == null || conditionConfig.resolvedPlaylist().isEmpty()) {
            return false;
        }
        if (!conditionConfig.repeatableInSameSession()) {
            Set<String> fired = firedConditions.computeIfAbsent(player.getUUID(), key -> new HashSet<>());
            if (!fired.add(conditionKey)) {
                return false;
            }
        }
        PlaybackControlNetworking.sendPlay(player, conditionKey);
        return true;
    }

    public static void clearPlayer(ServerPlayer player) {
        firedConditions.remove(player.getUUID());
    }

    public static ConditionalVideosConfig activeConfig(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (cachedConfig == null || now >= cacheExpiryMillis) {
            cachedConfig = loadActiveConfig(server);
            cacheExpiryMillis = now + CACHE_TTL_MILLIS;
        }
        return cachedConfig;
    }

    public static ConditionalVideosConfig loadActiveConfig(MinecraftServer server) {
        Path directory = server.getServerDirectory();
        if (server.isDedicatedServer()) {
            return ConditionalVideosConfig.loadServer(directory);
        }
        return ConditionalVideosConfig.load(ConditionalVideosConfig.clientConfigPath(directory));
    }
}
