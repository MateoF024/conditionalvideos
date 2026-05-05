package org.mateof24.conditionalvideos.condition.shared;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.join.SessionKeyResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;
import org.mateof24.conditionalvideos.video.path.VideoPathResolver;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;

import java.nio.file.Path;

public final class ConditionVideoPlayer {
    private ConditionVideoPlayer() {
    }

    public static boolean play(Minecraft minecraft,
                               ConditionalVideosConfig config,
                               ConditionalVideosConfig.ConditionConfig conditionConfig,
                               String conditionId,
                               String logName,
                               Runnable onCloseCallback) {
        if (conditionConfig == null || conditionConfig.video().isBlank()) {
            ConditionalVideos.LOGGER.debug("No {} video configured. Skipping playback.", logName);
            return false;
        }

        String sessionKey = null;
        if (!conditionConfig.repeatableInSameSession()) {
            sessionKey = SessionKeyResolver.resolveSessionKey(minecraft);
            if (config.hasConsumedConditionSession(conditionId, sessionKey)) {
                ConditionalVideos.LOGGER.debug("{} condition '{}' already consumed for session {}.", logName, conditionId, sessionKey);
                return false;
            }
        }

        Path resolvedPath = ActiveConfigResolver.resolveRemoteVideoPath(conditionConfig.video());
        if (resolvedPath == null) {
            resolvedPath = VideoPathResolver.resolve(minecraft.gameDirectory.toPath(), conditionConfig.video());
        }

        if (resolvedPath == null) {
            if (ActiveConfigResolver.isMultiplayerSession(minecraft)) {
                if (!ActiveConfigResolver.isManifestPending() && !ActiveConfigResolver.isVideoPathInManifest(conditionConfig.video())) {
                    ConditionalVideos.LOGGER.debug("Multiplayer {} video not locally available, skipping.", logName);
                }
                return false;
            }
            ConditionalVideos.LOGGER.warn("Configured {} video '{}' is invalid or not found. Ignoring.", logName, conditionConfig.video());
            return false;
        }

        if (!conditionConfig.repeatableInSameSession()) {
            config.markConditionSessionConsumed(conditionId, sessionKey);
            if (ActiveConfigResolver.shouldPersistLocalChanges(minecraft)) {
                config.save();
            }
        }

        final Runnable wrappedClose;
        if (ActiveConfigResolver.isMultiplayerSession(minecraft)) {
            ConfigSyncNetworking.notifyFirstJoinVideoState(true);
            wrappedClose = () -> {
                ConfigSyncNetworking.notifyFirstJoinVideoState(false);
                if (onCloseCallback != null) onCloseCallback.run();
            };
        } else if (minecraft.getSingleplayerServer() != null && minecraft.player != null) {
            net.minecraft.server.level.ServerPlayer serverPlayer =
                    minecraft.getSingleplayerServer().getPlayerList().getPlayer(minecraft.player.getUUID());
            if (serverPlayer != null) {
                GameType previous = serverPlayer.gameMode.getGameModeForPlayer();
                if (previous != GameType.SPECTATOR) {
                    serverPlayer.setGameMode(GameType.SPECTATOR);
                }
                final GameType finalPrevious = previous;
                wrappedClose = () -> {
                    if (minecraft.getSingleplayerServer() != null && minecraft.player != null) {
                        net.minecraft.server.level.ServerPlayer sp =
                                minecraft.getSingleplayerServer().getPlayerList().getPlayer(minecraft.player.getUUID());
                        if (sp != null) {
                            sp.setGameMode(finalPrevious);
                        }
                    }
                    if (onCloseCallback != null) onCloseCallback.run();
                };
            } else {
                wrappedClose = onCloseCallback;
            }
        } else {
            wrappedClose = onCloseCallback;
        }

        ConditionalVideos.LOGGER.info("Playing {} video: {}", logName, resolvedPath);
        int backgroundColor = config.resolveBackgroundColor(conditionConfig, 0xFF000000);
        minecraft.setScreen(new VideoPlaybackScreen(
                resolvedPath,
                conditionConfig.skippable(),
                conditionConfig.enableBackground(),
                backgroundColor,
                conditionConfig.videoTitle(),
                conditionConfig.videoTitlePosition(),
                conditionConfig.videoDescription(),
                conditionConfig.videoDescriptionPosition(),
                wrappedClose
        ));
        return true;
    }
}