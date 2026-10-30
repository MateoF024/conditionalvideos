package org.mateof24.conditionalvideos.world;

import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

// Who is left out of each condition (excludePlayers, excludeTeams, excludeOps). The server decides: every player
// is told their own list, and a change of team, OP or rules is noticed within a second and sent again.
public final class PlayerExclusions {
    private static final int CHECK_INTERVAL_TICKS = 20;

    private static final Map<UUID, Set<String>> SENT = new HashMap<>();
    // Conditions a player received despite being left out, because an order reached them anyway.
    private static final Map<UUID, Set<ConditionConfig>> DELIVERED = new HashMap<>();
    private static ConditionalVideosConfig rulesConfig;
    private static Map<String, ConditionConfig> rules = Map.of();
    private static int ticks;

    private PlayerExclusions() {
    }

    public static void init() {
        PlayerEvent.PLAYER_QUIT.register(player -> {
            SENT.remove(player.getUUID());
            DELIVERED.remove(player.getUUID());
        });
        TickEvent.SERVER_POST.register(PlayerExclusions::onServerTick);
    }

    // Why this player is left out of the condition; null when they are not.
    public static String reason(ServerPlayer player, ConditionConfig condition) {
        return condition == null ? null : condition.exclusionReason(PlayerRoles.viewer(player));
    }

    // Who a player is for what they receive: their own exclusions, minus what an order already had to deliver.
    public static ConditionalVideosConfig.Viewer deliveryViewer(ServerPlayer player) {
        ConditionalVideosConfig.Viewer viewer = PlayerRoles.viewer(player);
        Set<ConditionConfig> delivered = DELIVERED.get(player.getUUID());
        return delivered == null ? viewer : new ConditionalVideosConfig.Viewer(viewer.name(), viewer.team(), viewer.operator(), Set.copyOf(delivered));
    }

    // An order can reach someone left out of the condition, whose client received neither it nor its videos: they
    // get them first. A host has them in their own files. It still does not fire for them by itself, the excluded list keeps it out.
    public static void ensureDelivered(ServerPlayer player, String key) {
        MinecraftServer server = player.getServer();
        if (server == null || PlayerRoles.isHost(server, player)) {
            return;
        }
        ConditionConfig condition = ConditionRegistry.resolve(ServerConditionDispatcher.activeConfig(server), key);
        if (condition == null || condition.exclusionReason(deliveryViewer(player)) == null) {
            return;
        }
        DELIVERED.computeIfAbsent(player.getUUID(), id -> new HashSet<>()).add(condition);
        DebugLog.server(DebugLog.Area.CONFIG, "Delivering '{}' to {}, who is left out of it, for an order.", key, player.getScoreboardName());
        ConfigSyncNetworking.sendServerConfig(player);
        ConfigSyncNetworking.sendVideoManifest(player);
    }

    // Goes out before the watched list, which is what lets the client start deciding.
    public static void sendList(ServerPlayer player) {
        Set<String> keys = keysFor(player);
        SENT.put(player.getUUID(), keys);
        logReasons(player, keys);
        PlaybackControlNetworking.sendExcluded(player, keys);
    }

    private static void onServerTick(MinecraftServer server) {
        if (SENT.isEmpty() || ++ticks < CHECK_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Set<String> sent = SENT.get(player.getUUID());
            if (sent == null) {
                continue;
            }
            Set<String> keys = keysFor(player);
            if (keys.equals(sent)) {
                continue;
            }
            DebugLog.server(DebugLog.Area.CONFIG, "The exclusions of {} changed: {} -> {}.", player.getScoreboardName(), sent, keys);
            boolean regained = !keys.containsAll(sent);
            sendList(player);
            if (!PlayerRoles.isHost(server, player)) {
                ConfigSyncNetworking.sendServerConfig(player);
                if (regained) {
                    ConfigSyncNetworking.sendVideoManifest(player);
                }
            }
        }
    }

    private static Set<String> keysFor(ServerPlayer player) {
        Set<String> keys = new TreeSet<>();
        Map<String, ConditionConfig> current = rules(player.getServer());
        if (!current.isEmpty()) {
            ConditionalVideosConfig.Viewer viewer = PlayerRoles.viewer(player);
            current.forEach((key, condition) -> {
                if (condition.exclusionReason(viewer) != null) {
                    keys.add(key);
                }
            });
        }
        return keys;
    }

    // Only the conditions that have rules, rebuilt when the conditions change.
    private static Map<String, ConditionConfig> rules(MinecraftServer server) {
        ConditionalVideosConfig config = ServerConditionDispatcher.activeConfig(server);
        if (config != rulesConfig) {
            rulesConfig = config;
            Map<String, ConditionConfig> found = new LinkedHashMap<>();
            for (String key : ConditionRegistry.listPlayableKeys(config)) {
                ConditionConfig condition = ConditionRegistry.resolve(config, key);
                if (condition != null && condition.hasExclusions()) {
                    found.put(key, condition);
                }
            }
            rules = found;
        }
        return rules;
    }

    private static void logReasons(ServerPlayer player, Set<String> keys) {
        if (DebugLog.serverEnabled()) {
            ConditionalVideosConfig.Viewer viewer = PlayerRoles.viewer(player);
            for (String key : keys) {
                DebugLog.server(DebugLog.Area.CONFIG, "'{}' is left out for {}: {}.", key, player.getScoreboardName(), rules.get(key).exclusionReason(viewer));
            }
        }
    }

    public static void reset() {
        SENT.clear();
        DELIVERED.clear();
        rulesConfig = null;
        rules = Map.of();
        ticks = 0;
    }
}
