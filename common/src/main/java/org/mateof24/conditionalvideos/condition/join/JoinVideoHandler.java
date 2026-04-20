package org.mateof24.conditionalvideos.condition.join;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.*;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;
import org.mateof24.conditionalvideos.video.path.VideoPathResolver;

import java.nio.file.Path;

public final class JoinVideoHandler {
    private static final String CONDITION_ID = "firstJoin";

    private JoinVideoHandler() {
    }

    public static void onJoinedSession(Minecraft minecraft) {
        ConditionalVideosConfig config = ConditionalVideosConfig.load();
        ConditionalVideosConfig.ConditionConfig firstJoin = config.firstJoin();

        if (firstJoin == null || firstJoin.video().isBlank()) {
            ConditionalVideos.LOGGER.debug("No first join video configured. Skipping playback.");
            return;
        }

        boolean shouldTrackSession = config.shouldTrackSessionFor(firstJoin);
        String sessionKey = null;
        if (shouldTrackSession) {
            sessionKey = SessionKeyResolver.resolveSessionKey(minecraft);
            if (config.hasConsumedConditionSession(CONDITION_ID, sessionKey)) {
                ConditionalVideos.LOGGER.debug("First join condition already consumed for session {}.", sessionKey);
                return;
            }

            Path resolvedPath = VideoPathResolver.resolve(minecraft.gameDirectory.toPath(), firstJoin.video());
            if (resolvedPath == null) {
                ConditionalVideos.LOGGER.warn("Configured first join video '{}' is invalid or not found. Ignoring.", firstJoin.video());
                return;
            }

            if (shouldTrackSession && sessionKey != null) {
                config.markConditionSessionConsumed(CONDITION_ID, sessionKey);
                config.save();
            }

            int backgroundColor = config.resolveBackgroundColor(firstJoin, 0xFF000000);
            minecraft.setScreen(new VideoPlaybackScreen(
                    resolvedPath,
                    firstJoin.skippable(),
                    firstJoin.enableBackground(),
                    backgroundColor
            ));
        }
    }
}