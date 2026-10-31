package org.mateof24.conditionalvideos.debug;

import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.server.MinecraftServer;
import org.mateof24.conditionalvideos.ConditionalVideos;

// Debug logging split by side. The client and the server each enable it from their own debugLogging
// flag and tag every line with where it runs ([CV|CLIENT/REMOTE|AREA], [CV|SERVER/LAN|AREA], ...), so a
// singleplayer log keeps both sides apart. Each side writes to its own log; nothing crosses the network.
public final class DebugLog {
    public enum Area {
        LIFECYCLE, CONFIG, MIGRATION, WORLD, JOIN, CONDITION, SCHEDULE, ROOM, QUEUE, NETWORK, TRANSFER, SOURCE, BACKEND, QUALITY,
        PLAYLIST, PLAYBACK, RENDER, AUDIO, PROTECT, FREEZE, COMMAND, API, ENV, STATS
    }

    public static final String CLIENT_MENU = "CLIENT";
    public static final String CLIENT_LOCAL = "CLIENT/LOCAL";
    public static final String CLIENT_REMOTE = "CLIENT/REMOTE";

    private static volatile boolean clientEnabled;
    private static volatile boolean serverEnabled;
    private static volatile String clientSituation = CLIENT_MENU;
    private static volatile MinecraftServer server;

    private DebugLog() {
    }

    public static boolean clientEnabled() {
        return clientEnabled;
    }

    public static boolean serverEnabled() {
        return serverEnabled;
    }

    // Both setters return whether the flag changed, so the caller can react to it.
    public static boolean setClientEnabled(boolean value) {
        boolean changed = clientEnabled != value;
        clientEnabled = value;
        return changed;
    }

    public static boolean setServerEnabled(boolean value) {
        boolean changed = serverEnabled != value;
        serverEnabled = value;
        return changed;
    }

    public static void setClientSituation(String situation) {
        clientSituation = situation;
    }

    public static String clientSituation() {
        return clientSituation;
    }

    public static void setServer(MinecraftServer value) {
        server = value;
    }

    public static String serverSituation() {
        MinecraftServer current = server;
        if (current == null) {
            return Platform.getEnvironment() == Env.SERVER ? "SERVER/DEDICATED" : "SERVER";
        }
        if (current.isDedicatedServer()) {
            return "SERVER/DEDICATED";
        }
        return current.isPublished() ? "SERVER/LAN" : "SERVER/LOCAL";
    }

    public static void client(Area area, String format, Object... args) {
        if (clientEnabled) {
            ConditionalVideos.LOGGER.info("[CV|" + clientSituation + "|" + area.name() + "] " + format, args);
        }
    }

    public static void server(Area area, String format, Object... args) {
        if (serverEnabled) {
            ConditionalVideos.LOGGER.info("[CV|" + serverSituation() + "|" + area.name() + "] " + format, args);
        }
    }

    // Logs on the side this installation runs: the client for a game client, the server for a dedicated
    // server. Used for what belongs to the installation itself, such as its config folders.
    public static void side(Area area, String format, Object... args) {
        if (Platform.getEnvironment() == Env.SERVER) {
            server(area, format, args);
        } else {
            client(area, format, args);
        }
    }

    public static boolean sideEnabled() {
        return Platform.getEnvironment() == Env.SERVER ? serverEnabled : clientEnabled;
    }

    // Runtime and platform lines shared by the client and the server environment dumps.
    public static String runtimeDescription() {
        return "OS " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " | Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
    }

    public static String platformDescription() {
        boolean neoForge = Platform.isModLoaded("neoforge");
        String loader = Platform.isFabric() ? "Fabric" : neoForge ? "NeoForge" : "Forge";
        String loaderId = Platform.isFabric() ? "fabricloader" : neoForge ? "neoforge" : "forge";
        return "Minecraft " + Platform.getMinecraftVersion() + " | " + loader + " " + modVersion(loaderId)
                + " | ConditionalVideos " + modVersion(ConditionalVideos.MOD_ID)
                + " | Architectury " + modVersion("architectury");
    }

    public static String modVersion(String modId) {
        try {
            return Platform.isModLoaded(modId) ? Platform.getMod(modId).getVersion() : "absent";
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
