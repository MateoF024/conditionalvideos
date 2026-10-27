package org.mateof24.conditionalvideos.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.debug.ClientDebug;
import org.mateof24.conditionalvideos.debug.DebugLog;

import java.nio.file.Path;
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

    private static final long LOCAL_CONFIG_TTL_MILLIS = 3000L;
    private static ConditionalVideosConfig localConfigCache;
    private static long localConfigCacheExpiry;
    private static int loggedLocalPlayable = -1;

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
        DebugLog.client(DebugLog.Area.CONFIG, "Local common config '{}': videoQuality={}, alwaysShowCursor={}, allowGameSounds={}, blockMatureContent={}, videoLoadTimeoutSeconds={}.",
                CommonConfig.configPath(), localCommonConfig.videoQuality(), localCommonConfig.alwaysShowCursor(),
                localCommonConfig.allowGameSounds(), localCommonConfig.blockMatureContent(), localCommonConfig.videoLoadTimeoutSeconds());
    }

    public static void setRemoteCommonConfig(CommonConfig config) {
        remoteCommonConfig = config;
        DebugLog.client(DebugLog.Area.CONFIG, "The server dictates: videoQuality={}, alwaysShowCursor={}, allowGameSounds={}.",
                config.videoQuality(), config.alwaysShowCursor(), config.allowGameSounds());
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
        return localCommonConfig().videoLoadTimeoutSeconds();
    }

    public static ConditionalVideosConfig resolve(Minecraft minecraft) {
        if (!isMultiplayerSession(minecraft)) {
            long now = System.currentTimeMillis();
            if (localConfigCache == null || now >= localConfigCacheExpiry) {
                localConfigCache = ConditionalVideosConfig.load();
                localConfigCacheExpiry = now + LOCAL_CONFIG_TTL_MILLIS;
                if (DebugLog.clientEnabled()) {
                    List<String> playable = ConditionRegistry.listPlayableKeys(localConfigCache);
                    if (playable.size() != loggedLocalPlayable) {
                        loggedLocalPlayable = playable.size();
                        DebugLog.client(DebugLog.Area.CONFIG, "Using the local conditions file: {} playable {}.", playable.size(), playable);
                    }
                }
            }
            return localConfigCache;
        }

        if (remoteConfigState == RemoteConfigState.AVAILABLE && remoteConfig != null) {
            return remoteConfig;
        }

        return new ConditionalVideosConfig();
    }

    public static void invalidateLocalConfigCache() {
        localConfigCache = null;
        localConfigCacheExpiry = 0L;
        loggedLocalPlayable = -1;
    }

    public static boolean shouldPersistLocalChanges(Minecraft minecraft) {
        return !isMultiplayerSession(minecraft);
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

    public static String resolveCurrentServerId(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        if (server == null) {
            return "unknown-server";
        }
        String source = server.ip != null && !server.ip.isBlank() ? server.ip : server.name;
        if (source == null || source.isBlank()) {
            source = "unknown-server";
        }
        return source.replaceAll("[^a-zA-Z0-9._-]", "_");
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
