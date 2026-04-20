package org.mateof24.conditionalvideos.condition.death;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.join.SessionKeyResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;
import org.mateof24.conditionalvideos.video.path.VideoPathResolver;

import java.nio.file.Path;

public final class PlayerDeathVideoHandler {
    private static final String CONDITION_ID = "playerDeath";

    private PlayerDeathVideoHandler() {
    }

    public static void onPlayerDied(Minecraft minecraft) {
        ConditionalVideosConfig config = ConditionalVideosConfig.load();
        ConditionalVideosConfig.ConditionConfig playerDeath = config.playerDeath();

        if (playerDeath == null || playerDeath.video().isBlank()) {
            ConditionalVideos.LOGGER.debug("No player death video configured. Skipping playback.");
            return;
        }

        boolean shouldTrackSession = config.shouldTrackSessionFor(playerDeath);
        String sessionKey = null;
        if (shouldTrackSession) {
            sessionKey = SessionKeyResolver.resolveSessionKey(minecraft);
            if (config.hasConsumedConditionSession(CONDITION_ID, sessionKey)) {
                ConditionalVideos.LOGGER.debug("Player death condition already consumed for session {}.", sessionKey);
                return;
            }
        }

        Path resolvedPath = VideoPathResolver.resolve(minecraft.gameDirectory.toPath(), playerDeath.video());
        if (resolvedPath == null) {
            ConditionalVideos.LOGGER.warn("Configured player death video '{}' is invalid or not found. Ignoring.", playerDeath.video());
            return;
        }

        if (shouldTrackSession && sessionKey != null) {
            config.markConditionSessionConsumed(CONDITION_ID, sessionKey);
            config.save();
        }

        int backgroundColor = config.resolveBackgroundColor(playerDeath, 0xFF000000);
        minecraft.setScreen(new VideoPlaybackScreen(
                resolvedPath,
                playerDeath.skippable(),
                playerDeath.enableBackground(),
                backgroundColor
        ));
    }
}