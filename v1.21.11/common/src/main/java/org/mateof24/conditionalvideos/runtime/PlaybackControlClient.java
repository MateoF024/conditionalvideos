package org.mateof24.conditionalvideos.runtime;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
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

    public static void stop() {
        Minecraft minecraft = Minecraft.getInstance();
        ConditionVideoPlayer.clearQueue();
        if (minecraft.screen instanceof VideoPlaybackScreen screen) {
            screen.onClose();
        }
    }

    public static void togglePause() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof VideoPlaybackScreen screen) {
            screen.togglePause();
        }
    }
}
