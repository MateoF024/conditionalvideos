package org.mateof24.conditionalvideos;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;

final class JoinVideoHandler {
    private JoinVideoHandler() {
    }

    static void onJoinedSession(Minecraft minecraft) {
        ConditionalVideosConfig config = ConditionalVideosConfig.load();
        ConditionalVideosConfig.FirstJoinConfig firstJoin = config.firstJoin();

        if (firstJoin == null || firstJoin.video().isBlank()) {
            ConditionalVideos.LOGGER.debug("No first join video configured. Skipping playback.");
            return;
        }

        String sessionKey = SessionKeyResolver.resolveSessionKey(minecraft);
        if (config.hasSeenSession(sessionKey)) {
            ConditionalVideos.LOGGER.debug("First join condition already consumed for session {}.", sessionKey);
            return;
        }

        Path resolvedPath = VideoPathResolver.resolve(minecraft.gameDirectory.toPath(), firstJoin.video());
        if (resolvedPath == null) {
            ConditionalVideos.LOGGER.warn("Configured first join video '{}' is invalid or not found. Ignoring.", firstJoin.video());
            return;
        }

        config.markSessionSeen(sessionKey);
        config.save();

        minecraft.setScreen(new VideoPlaybackScreen(resolvedPath));
    }
}