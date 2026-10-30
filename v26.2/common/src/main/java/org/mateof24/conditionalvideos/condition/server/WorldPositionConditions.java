package org.mateof24.conditionalvideos.condition.server;

import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.WorldPositionConditionConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.world.PlaybackSessions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Fires worldPosition conditions when a player's feet cross an area. Every connection starts outside, so joining
// inside counts as entering; teleports, portals, respawns and dimension changes are just more crossings.
public final class WorldPositionConditions {
    private static final Map<UUID, Set<String>> INSIDE = new HashMap<>();

    private WorldPositionConditions() {
    }

    public static void init() {
        PlayerEvent.PLAYER_QUIT.register(player -> INSIDE.remove(player.getUUID()));
        TickEvent.SERVER_POST.register(WorldPositionConditions::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        ConditionalVideosConfig config = ServerConditionDispatcher.activeConfig(server);
        Map<String, WorldPositionConditionConfig> areas = config.worldPosition();
        if (areas.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Set<String> inside = INSIDE.computeIfAbsent(player.getUUID(), id -> new HashSet<>());
            String dimension = PlaybackSessions.dimensionOf(player);
            BlockPos feet = player.blockPosition();
            for (Map.Entry<String, WorldPositionConditionConfig> entry : areas.entrySet()) {
                String key = entry.getKey();
                WorldPositionConditionConfig area = entry.getValue();
                boolean now = area.contains(dimension, feet.getX(), feet.getY(), feet.getZ());
                if (now == inside.contains(key)) {
                    continue;
                }
                if (now) {
                    inside.add(key);
                } else {
                    inside.remove(key);
                }
                DebugLog.server(DebugLog.Area.CONDITION, "{} {} the area '{}' at {} {} {} in {}.", player.getScoreboardName(), now ? "entered" : "left", key,
                        feet.getX(), feet.getY(), feet.getZ(), dimension);
                if (now == area.firesOnEnter()) {
                    ServerConditionDispatcher.fire(player, ConditionRegistry.TYPE_WORLD_POSITION + "/" + key);
                }
            }
        }
    }

    public static void reset() {
        INSIDE.clear();
    }
}
