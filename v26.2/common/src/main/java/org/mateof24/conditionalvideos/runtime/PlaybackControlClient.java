package org.mateof24.conditionalvideos.runtime;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.video.SyncPoint;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;

public final class PlaybackControlClient {
    private PlaybackControlClient() {
    }

    public static void play(String conditionKey) {
        Minecraft minecraft = Minecraft.getInstance();
        ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
        ConditionalVideosConfig.ConditionConfig conditionConfig = ConditionRegistry.resolve(config, conditionKey);
        if (conditionConfig == null || conditionConfig.resolvedPlaylist().isEmpty()) {
            ConditionalVideos.LOGGER.warn("Server requested playback of condition '{}' but it has no videos in the active config.", conditionKey);
            return;
        }
        ConditionVideoPlayer.playForced(minecraft, config, conditionConfig, conditionKey, "command/" + conditionKey);
    }

    // Joins others watching this condition at the point they are.
    public static void playAt(String conditionKey, int entry, double seconds) {
        Minecraft minecraft = Minecraft.getInstance();
        ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
        ConditionalVideosConfig.ConditionConfig conditionConfig = ConditionRegistry.resolve(config, conditionKey);
        if (conditionConfig == null || conditionConfig.resolvedPlaylist().isEmpty()) {
            ConditionalVideos.LOGGER.warn("Server requested playback of condition '{}' but it has no videos in the active config.", conditionKey);
            return;
        }
        ConditionVideoPlayer.playForcedAt(minecraft, config, conditionConfig, conditionKey, "command/" + conditionKey, new SyncPoint(entry, seconds, System.nanoTime()));
    }

    // Tells the server where this client is in the condition, so someone else can join.
    public static void reportPosition(String conditionKey) {
        double seconds = -1d;
        int entry = 0;
        if (Minecraft.getInstance().gui.screen() instanceof VideoPlaybackScreen screen && conditionKey.equals(screen.conditionKey())) {
            seconds = screen.shownPositionSeconds();
            entry = screen.currentEntryIndex();
        }
        PlaybackControlNetworking.sendPosition(conditionKey, seconds >= 0d, entry, Math.max(0d, seconds));
    }

    public static void stop() {
        Minecraft minecraft = Minecraft.getInstance();
        ConditionVideoPlayer.clearQueue();
        if (minecraft.gui.screen() instanceof VideoPlaybackScreen screen) {
            DebugLog.client(DebugLog.Area.PLAYLIST, "Stop requested: closing the active video.");
            screen.onClose();
        } else {
            DebugLog.client(DebugLog.Area.PLAYLIST, "Stop requested, but no video is playing.");
        }
    }

    public static void togglePause() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() instanceof VideoPlaybackScreen screen) {
            screen.togglePause();
        } else {
            DebugLog.client(DebugLog.Area.PLAYLIST, "Pause toggle requested, but no video is playing.");
        }
    }
}
