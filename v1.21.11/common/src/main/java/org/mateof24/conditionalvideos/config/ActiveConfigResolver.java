package org.mateof24.conditionalvideos.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.debug.ClientDebug;
import org.mateof24.conditionalvideos.debug.DebugLog;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ActiveConfigResolver {
    public enum RemoteConfigState {
        UNKNOWN,
        AVAILABLE,
        UNAVAILABLE
    }

    private static ConditionalVideosConfig remoteConfig;
    private static RemoteConfigState remoteConfigState = RemoteConfigState.UNKNOWN;
    private static final Map<String, Path> remoteVideoPaths = new HashMap<>();
    private static final Set<String> manifestedRemoteVideoPaths = new HashSet<>();
    private static volatile boolean manifestReceived = false;
    private static volatile boolean manifestProcessed = true;

    private static CommonConfig localCommonConfig;
    private static CommonConfig remoteCommonConfig;

    // Conditions already watched in the current world: the list the server sent on join plus every one
    // started since. A server that sends no list (an older version) leaves it per connection.
    private static final Set<String> WATCHED = new HashSet<>();
    private static boolean watchedFromServer;
    // Conditions the server left this player out of. Mainly for a host, who reads their own files.
    private static final Set<String> EXCLUDED = new HashSet<>();

    private ActiveConfigResolver() {
    }

    public static CommonConfig localCommonConfig() {
        if (localCommonConfig == null) {
            reloadLocalCommonConfig();
        }
        return localCommonConfig;
    }

    public static void reloadLocalCommonConfig() {
        localCommonConfig = CommonConfig.load();
        ClientDebug.applyFlag(localCommonConfig.debugLogging());
        DebugLog.client(DebugLog.Area.CONFIG, "Local common config '{}': videoQuality={}, alwaysShowCursor={}, allowGameSounds={}, blockMatureContent={}, videoLoadTimeoutSeconds={}, defaultVolume={}.",
                CommonConfig.configPath(), localCommonConfig.videoQuality(), localCommonConfig.alwaysShowCursor(),
                localCommonConfig.allowGameSounds(), localCommonConfig.blockMatureContent(), localCommonConfig.videoLoadTimeoutSeconds(), localCommonConfig.defaultVolume());
    }

    public static void setRemoteCommonConfig(CommonConfig config) {
        remoteCommonConfig = config;
        DebugLog.client(DebugLog.Area.CONFIG, "The server dictates: videoQuality={}, alwaysShowCursor={}, allowGameSounds={}, videoLoadTimeoutSeconds={}, defaultVolume={}.",
                config.videoQuality(), config.alwaysShowCursor(), config.allowGameSounds(), config.videoLoadTimeoutSeconds(), config.defaultVolume());
    }

    private static CommonConfig authoritativeCommonConfig(Minecraft minecraft) {
        if (isMultiplayerSession(minecraft) && remoteCommonConfig != null) {
            return remoteCommonConfig;
        }
        return localCommonConfig();
    }

    public static String effectiveVideoQuality(Minecraft minecraft) {
        return authoritativeCommonConfig(minecraft).videoQuality();
    }

    public static boolean effectiveAlwaysShowCursor(Minecraft minecraft) {
        return authoritativeCommonConfig(minecraft).alwaysShowCursor();
    }

    public static boolean effectiveAllowGameSounds(Minecraft minecraft) {
        return authoritativeCommonConfig(minecraft).allowGameSounds();
    }

    public static boolean effectiveBlockMatureContent() {
        return localCommonConfig().blockMatureContent();
    }

    public static int effectiveVideoLoadTimeoutSeconds() {
        return authoritativeCommonConfig(Minecraft.getInstance()).videoLoadTimeoutSeconds();
    }

    public static float effectiveDefaultVolume(Minecraft minecraft) {
        return authoritativeCommonConfig(minecraft).defaultVolume();
    }

    public static ConditionalVideosConfig resolve(Minecraft minecraft) {
        if (!isMultiplayerSession(minecraft)) {
            return ConditionFiles.client().config();
        }

        if (remoteConfigState == RemoteConfigState.AVAILABLE && remoteConfig != null) {
            return remoteConfig;
        }

        return new ConditionalVideosConfig();
    }

    public static void invalidateLocalConfigCache() {
        ConditionFiles.client().invalidate();
    }

    public static boolean isExcluded(String conditionKey) {
        return EXCLUDED.contains(conditionKey);
    }

    public static void setExcludedFromServer(Collection<String> conditionKeys) {
        EXCLUDED.clear();
        EXCLUDED.addAll(conditionKeys);
    }

    public static boolean hasWatched(String conditionKey) {
        return WATCHED.contains(conditionKey);
    }

    public static void markWatched(String conditionKey) {
        WATCHED.add(conditionKey);
    }

    public static void setWatchedFromServer(Collection<String> conditionKeys) {
        WATCHED.clear();
        WATCHED.addAll(conditionKeys);
        watchedFromServer = true;
    }

    public static boolean watchedFromServer() {
        return watchedFromServer;
    }

    public static boolean isMultiplayerSession(Minecraft minecraft) {
        return minecraft.level != null && !minecraft.hasSingleplayerServer();
    }

    public static void setRemoteConfig(ConditionalVideosConfig config) {
        remoteConfig = config;
        remoteConfigState = RemoteConfigState.AVAILABLE;
        if (DebugLog.clientEnabled()) {
            List<String> playable = ConditionRegistry.listPlayableKeys(config);
            DebugLog.client(DebugLog.Area.CONFIG, "Using the server's conditions: {} playable {}.", playable.size(), playable);
        }
    }

    public static void resetRemoteSessionState() {
        remoteConfig = null;
        remoteConfigState = RemoteConfigState.UNKNOWN;
        remoteVideoPaths.clear();
        manifestedRemoteVideoPaths.clear();
        manifestReceived = false;
        manifestProcessed = true;
        remoteCommonConfig = null;
        WATCHED.clear();
        EXCLUDED.clear();
        watchedFromServer = false;
    }

    public static RemoteConfigState remoteConfigState() {
        return remoteConfigState;
    }

    public static void markRemoteUnavailable() {
        if (remoteConfigState == RemoteConfigState.UNKNOWN) {
            remoteConfigState = RemoteConfigState.UNAVAILABLE;
        }
    }

    public static void setRemoteVideoPath(String configuredPath, Path localPath) {
        remoteVideoPaths.put(configuredPath, localPath);
    }

    public static Path resolveRemoteVideoPath(String configuredPath) {
        return remoteVideoPaths.get(configuredPath);
    }

    public static void addManifestedVideoPath(String configuredPath) {
        manifestedRemoteVideoPaths.add(configuredPath);
    }

    public static boolean isVideoPathInManifest(String configuredPath) {
        return manifestedRemoteVideoPaths.contains(configuredPath);
    }

    // Folder name of this server's video cache inside config/conditionalvideos/. It never takes the name of
    // a conditions folder, which lives in the same place.
    public static String resolveCurrentServerId(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        if (server == null) {
            return "unknown-server";
        }
        String source = server.ip != null && !server.ip.isBlank() ? server.ip : server.name;
        if (source == null || source.isBlank()) {
            source = "unknown-server";
        }
        String id = source.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (id.equalsIgnoreCase(ConditionFiles.CLIENT_FOLDER) || id.equalsIgnoreCase(ConditionFiles.SERVER_FOLDER)) {
            id += "_";
        }
        return id;
    }

    public static void onManifestReceived() {
        manifestReceived = true;
        manifestProcessed = false;
    }

    public static void onManifestProcessed() {
        manifestProcessed = true;
    }

    public static boolean isManifestPending() {
        return manifestReceived && !manifestProcessed;
    }
}
