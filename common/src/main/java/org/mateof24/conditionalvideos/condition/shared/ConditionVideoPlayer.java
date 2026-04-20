package org.mateof24.conditionalvideos.condition.shared;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.join.SessionKeyResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;
import org.mateof24.conditionalvideos.video.path.VideoPathResolver;

import java.nio.file.Path;

public final class ConditionVideoPlayer {
    private ConditionVideoPlayer() {
    }

    public static boolean play(Minecraft minecraft,
                               ConditionalVideosConfig config,
                               ConditionalVideosConfig.ConditionConfig conditionConfig,
                               String conditionId,
                               String logName) {
        if (conditionConfig == null || conditionConfig.video().isBlank()) {
            ConditionalVideos.LOGGER.debug("No {} video configured. Skipping playback.", logName);
            return false;
        }

        if (!conditionConfig.repeatableInSameSession()) {
            String sessionKey = SessionKeyResolver.resolveSessionKey(minecraft);
            if (config.hasConsumedConditionSession(conditionId, sessionKey)) {
                ConditionalVideos.LOGGER.debug("{} condition '{}' already consumed for session {}.", logName, conditionId, sessionKey);
                return false;
            }
            config.markConditionSessionConsumed(conditionId, sessionKey);
            config.save();
        }

        Path resolvedPath = VideoPathResolver.resolve(minecraft.gameDirectory.toPath(), conditionConfig.video());
        if (resolvedPath == null) {
            ConditionalVideos.LOGGER.warn("Configured {} video '{}' is invalid or not found. Ignoring.", logName, conditionConfig.video());
            return false;
        }

        int backgroundColor = config.resolveBackgroundColor(conditionConfig, 0xFF000000);
        minecraft.setScreen(new VideoPlaybackScreen(
                resolvedPath,
                conditionConfig.skippable(),
                conditionConfig.enableBackground(),
                backgroundColor,
                conditionConfig.videoTitle(),
                conditionConfig.videoTitlePosition(),
                conditionConfig.videoDescription(),
                conditionConfig.videoDescriptionPosition()
        ));
        return true;
    }
}