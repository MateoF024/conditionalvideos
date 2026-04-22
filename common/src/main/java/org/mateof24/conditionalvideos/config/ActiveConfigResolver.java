package org.mateof24.conditionalvideos.config;

import net.minecraft.client.Minecraft;

public final class ActiveConfigResolver {
    public enum RemoteConfigState {
        UNKNOWN,
        AVAILABLE,
        UNAVAILABLE
    }

    private static ConditionalVideosConfig remoteConfig;
    private static RemoteConfigState remoteConfigState = RemoteConfigState.UNKNOWN;

    private ActiveConfigResolver() {
    }

    public static ConditionalVideosConfig resolve(Minecraft minecraft) {
        if (!isMultiplayerSession(minecraft)) {
            return ConditionalVideosConfig.load();
        }

        if (remoteConfigState == RemoteConfigState.AVAILABLE && remoteConfig != null) {
            return remoteConfig;
        }

        return new ConditionalVideosConfig();
    }

    public static boolean shouldPersistLocalChanges(Minecraft minecraft) {
        return !isMultiplayerSession(minecraft);
    }

    public static boolean isMultiplayerSession(Minecraft minecraft) {
        return minecraft.getCurrentServer() != null;
    }

    public static void setRemoteConfig(ConditionalVideosConfig config) {
        remoteConfig = config;
        remoteConfigState = RemoteConfigState.AVAILABLE;
    }

    public static void resetRemoteSessionState() {
        remoteConfig = null;
        remoteConfigState = RemoteConfigState.UNKNOWN;
    }

    public static RemoteConfigState remoteConfigState() {
        return remoteConfigState;
    }

    public static void markRemoteUnavailable() {
        if (remoteConfigState == RemoteConfigState.UNKNOWN) {
            remoteConfigState = RemoteConfigState.UNAVAILABLE;
        }
    }
}