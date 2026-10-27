package org.mateof24.conditionalvideos.debug;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.CommonConfig;
import org.mateof24.conditionalvideos.config.ConditionFiles;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Server side of the debug log. It is enabled from the server's own common config (never from a client),
// dumps the server environment when the server starts, and prints statistics to the server console every
// minute. The counters only move while the server debug log is on.
public final class ServerDebug {
    private static final int STATS_INTERVAL_TICKS = 20 * 60;
    private static final int SITUATION_CHECK_TICKS = 20;

    private static final Map<String, Integer> ORDERS = new TreeMap<>();
    private static int commands;
    private static int apiCalls;
    private static int transfersStarted;
    private static int transfersFinished;
    private static int transfersFailed;
    private static long bytesSent;
    private static int ticks;
    private static int statsGeneration;
    private static String lastSituation;

    private ServerDebug() {
    }

    public static void init() {
        if (Platform.getEnvironment() == Env.SERVER) {
            applyFlag(CommonConfig.readDebugLogging(CommonConfig.configPath()));
        }
        // Registered before the other SERVER_STARTING listeners, so their logs already see the flag.
        LifecycleEvent.SERVER_STARTING.register(ServerDebug::onServerStarting);
        LifecycleEvent.SERVER_STARTED.register(ServerDebug::onServerStarted);
        LifecycleEvent.SERVER_STOPPING.register(ServerDebug::onServerStopping);
        TickEvent.SERVER_POST.register(ServerDebug::onServerTick);
    }

    // Applies the server's own debugLogging flag, reacting only to a real change.
    public static void applyFlag(boolean enabled) {
        if (DebugLog.setServerEnabled(enabled)) {
            ConditionalVideos.LOGGER.info("ConditionalVideos server debug logging {}.", enabled ? "ENABLED" : "disabled");
        }
    }

    private static void onServerStarting(MinecraftServer server) {
        DebugLog.setServer(server);
        applyFlag(CommonConfig.readDebugLogging(CommonConfig.configPath(serverDirectory(server))));
        synchronized (ServerDebug.class) {
            resetCounters();
        }
        ticks = 0;
        statsGeneration = 0;
        lastSituation = DebugLog.serverSituation();
    }

    private static void onServerStarted(MinecraftServer server) {
        DebugLog.server(DebugLog.Area.LIFECYCLE, "Server started.");
        if (DebugLog.serverEnabled()) {
            dumpEnvironment(server);
        }
    }

    private static void onServerStopping(MinecraftServer server) {
        DebugLog.server(DebugLog.Area.LIFECYCLE, "Server stopping.");
        DebugLog.setServer(null);
        if (!server.isDedicatedServer()) {
            // An integrated server re-reads the flag when the next world starts.
            DebugLog.setServerEnabled(false);
        }
    }

    private static void onServerTick(MinecraftServer server) {
        if (!DebugLog.serverEnabled()) {
            return;
        }
        ticks++;
        if (ticks % SITUATION_CHECK_TICKS == 0) {
            String situation = DebugLog.serverSituation();
            if (!situation.equals(lastSituation)) {
                DebugLog.server(DebugLog.Area.LIFECYCLE, "Situation changed from {} (e.g. the world was opened to LAN).", lastSituation);
                lastSituation = situation;
            }
        }
        if (ticks % STATS_INTERVAL_TICKS == 0) {
            logStats(server);
        }
    }

    public static void countOrder(String conditionKey) {
        if (DebugLog.serverEnabled()) {
            synchronized (ServerDebug.class) {
                ORDERS.merge(conditionKey, 1, Integer::sum);
            }
        }
    }

    public static void countCommand() {
        if (DebugLog.serverEnabled()) {
            synchronized (ServerDebug.class) {
                commands++;
            }
        }
    }

    public static void countApiCall() {
        if (DebugLog.serverEnabled()) {
            synchronized (ServerDebug.class) {
                apiCalls++;
            }
        }
    }

    public static void countTransferStarted() {
        if (DebugLog.serverEnabled()) {
            synchronized (ServerDebug.class) {
                transfersStarted++;
            }
        }
    }

    public static void countTransferFinished(long bytes) {
        if (DebugLog.serverEnabled()) {
            synchronized (ServerDebug.class) {
                transfersFinished++;
                bytesSent += bytes;
            }
        }
    }

    public static void countTransferFailed() {
        if (DebugLog.serverEnabled()) {
            synchronized (ServerDebug.class) {
                transfersFailed++;
            }
        }
    }

    private static void logStats(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            names.add(player.getScoreboardName());
        }
        DebugLog.server(DebugLog.Area.STATS, "Players online {}/{}: {}", names.size(),
                server.getPlayerList().getMaxPlayers(), names.isEmpty() ? "-" : String.join(", ", names));
        List<String> protectedNames = ConfigSyncNetworking.protectedPlayerNames(server);
        if (!protectedNames.isEmpty()) {
            DebugLog.server(DebugLog.Area.STATS, "In firstJoin protection (spectator): {}", String.join(", ", protectedNames));
        }
        synchronized (ServerDebug.class) {
            int orders = ORDERS.values().stream().mapToInt(Integer::intValue).sum();
            if (orders > 0 || commands > 0 || apiCalls > 0) {
                DebugLog.server(DebugLog.Area.STATS, "Last minute: {} play order(s) {}, {} command(s), {} API call(s)",
                        orders, ORDERS, commands, apiCalls);
            }
            if (transfersStarted > 0 || transfersFinished > 0 || transfersFailed > 0) {
                DebugLog.server(DebugLog.Area.STATS, "Last minute transfers: {} started, {} finished, {} failed, {} KB sent ({} KB/s)",
                        transfersStarted, transfersFinished, transfersFailed, bytesSent / 1024, bytesSent / 1024 / 60);
            }
            resetCounters();
        }
        ConditionFiles files = ConditionFiles.active(server.isDedicatedServer());
        int generation = files.generation();
        if (statsGeneration != 0 && generation != statsGeneration) {
            DebugLog.server(DebugLog.Area.STATS, "Last minute config: {} reload(s), {} problem(s) now", generation - statsGeneration,
                    files.notices().size());
        }
        statsGeneration = generation;
    }

    private static void resetCounters() {
        ORDERS.clear();
        commands = 0;
        apiCalls = 0;
        transfersStarted = 0;
        transfersFinished = 0;
        transfersFailed = 0;
        bytesSent = 0L;
    }

    // One-shot server snapshot: platform, situation and the conditions this server serves.
    private static void dumpEnvironment(MinecraftServer server) {
        Path directory = serverDirectory(server);
        ConditionFiles files = ConditionFiles.active(server.isDedicatedServer());
        DebugLog.server(DebugLog.Area.ENV, "=== ConditionalVideos server environment ===");
        DebugLog.server(DebugLog.Area.ENV, "{}", DebugLog.runtimeDescription());
        DebugLog.server(DebugLog.Area.ENV, "{}", DebugLog.platformDescription());
        DebugLog.server(DebugLog.Area.ENV, "Server directory '{}' | max players {}", directory,
                server.getPlayerList().getMaxPlayers());
        try {
            Map<String, Integer> byType = new TreeMap<>();
            for (String key : ConditionRegistry.listPlayableKeys(ServerConditionDispatcher.activeConfig(server))) {
                int slash = key.indexOf('/');
                byType.merge(slash < 0 ? key : key.substring(0, slash), 1, Integer::sum);
            }
            DebugLog.server(DebugLog.Area.ENV, "Conditions folder '{}': playable {} | {} problem(s)", files.directory(), byType,
                    files.notices().size());
        } catch (Throwable t) {
            DebugLog.server(DebugLog.Area.ENV, "Conditions folder '{}': unreadable ({})", files.directory(), t.toString());
        }
        DebugLog.server(DebugLog.Area.ENV, "Common config '{}'", CommonConfig.configPath(directory));
        DebugLog.server(DebugLog.Area.ENV, "WaterMedia on this side: {} | Binaries {}",
                DebugLog.modVersion("watermedia"), DebugLog.modVersion("watermedia_binaries"));
        DebugLog.server(DebugLog.Area.ENV, "=== end of server environment ===");
    }

    private static Path serverDirectory(MinecraftServer server) {
        return server.getServerDirectory().toPath();
    }
}
