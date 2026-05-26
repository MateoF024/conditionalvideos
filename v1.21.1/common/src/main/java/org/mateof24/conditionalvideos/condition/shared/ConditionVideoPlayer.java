package org.mateof24.conditionalvideos.condition.shared;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.join.SessionKeyResolver;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.VideoEntry;
import org.mateof24.conditionalvideos.video.VideoLoadingScreen;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;
import org.mateof24.conditionalvideos.video.path.VideoPathResolver;
import org.mateof24.conditionalvideos.video.path.VideoSourceResolver;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

public final class ConditionVideoPlayer {
    private static final int LOADING_SCREEN_TIMEOUT_TICKS = 20 * 60;

    private ConditionVideoPlayer() {
    }

    public static boolean play(Minecraft minecraft,
                               ConditionalVideosConfig config,
                               ConditionalVideosConfig.ConditionConfig conditionConfig,
                               String conditionId,
                               String logName,
                               Runnable onCloseCallback) {
        if (conditionConfig == null) {
            ConditionalVideos.LOGGER.debug("No {} video configured. Skipping playback.", logName);
            return false;
        }

        List<VideoEntry> playlist = conditionConfig.resolvedPlaylist();
        if (playlist.isEmpty()) {
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

        Function<VideoEntry, URI> sourceResolver = entry -> resolveSource(minecraft, entry.source(), logName);

        VideoEntry firstEntry = playlist.get(0);
        URI firstSourceUri = sourceResolver.apply(firstEntry);
        if (firstSourceUri == null) {
            String configuredPath = firstEntry.source();
            if (isDownloadPending(minecraft, configuredPath)) {
                if (minecraft.screen instanceof VideoLoadingScreen) {
                    return true;
                }
                openLoadingScreen(minecraft, config, conditionConfig, conditionId, sessionKey,
                        playlist, sourceResolver, configuredPath, logName, onCloseCallback);
                return true;
            }
            return shouldGiveUpOnSource(configuredPath, logName);
        }

        startPlayback(minecraft, config, conditionConfig, conditionId, sessionKey,
                playlist, sourceResolver, firstSourceUri, onCloseCallback);
        return true;
    }

    private static void openLoadingScreen(Minecraft minecraft,
                                          ConditionalVideosConfig config,
                                          ConditionalVideosConfig.ConditionConfig conditionConfig,
                                          String conditionId,
                                          String sessionKey,
                                          List<VideoEntry> playlist,
                                          Function<VideoEntry, URI> sourceResolver,
                                          String configuredPath,
                                          String logName,
                                          Runnable onCloseCallback) {
        ConditionalVideos.LOGGER.debug("Opened video loading screen for {} video '{}'.", logName, configuredPath);
        VideoEntry firstEntry = playlist.get(0);
        VideoLoadingScreen screen = new VideoLoadingScreen(
                () -> ActiveConfigResolver.resolveRemoteVideoPath(configuredPath) != null,
                () -> {
                    URI readyUri = sourceResolver.apply(firstEntry);
                    if (readyUri == null) {
                        ConditionalVideos.LOGGER.warn("{} video '{}' was reported ready but its URI could not be resolved.",
                                logName, configuredPath);
                        if (minecraft.screen instanceof VideoLoadingScreen) {
                            minecraft.setScreen(null);
                        }
                        if (onCloseCallback != null) {
                            onCloseCallback.run();
                        }
                        return;
                    }
                    startPlayback(minecraft, config, conditionConfig, conditionId, sessionKey,
                            playlist, sourceResolver, readyUri, onCloseCallback);
                },
                () -> {
                    ConditionalVideos.LOGGER.warn("Timed out waiting for {} video '{}'.", logName, configuredPath);
                    if (minecraft.screen instanceof VideoLoadingScreen) {
                        minecraft.setScreen(null);
                    }
                    if (onCloseCallback != null) {
                        onCloseCallback.run();
                    }
                },
                LOADING_SCREEN_TIMEOUT_TICKS
        );
        minecraft.setScreen(screen);
    }

    private static void startPlayback(Minecraft minecraft,
                                      ConditionalVideosConfig config,
                                      ConditionalVideosConfig.ConditionConfig conditionConfig,
                                      String conditionId,
                                      String sessionKey,
                                      List<VideoEntry> playlist,
                                      Function<VideoEntry, URI> sourceResolver,
                                      URI firstSourceUri,
                                      Runnable onCloseCallback) {
        if (!conditionConfig.repeatableInSameSession()) {
            config.markConditionSessionConsumed(conditionId, sessionKey);
            if (ActiveConfigResolver.shouldPersistLocalChanges(minecraft)) {
                config.save();
            }
        }

        VideoEntry firstEntry = playlist.get(0);
        if (playlist.size() == 1 && !conditionConfig.resolvedPlaylistLoop()) {
            String declaredTransition = firstEntry.transition();
            if (declaredTransition != null && !declaredTransition.isBlank()) {
                ConditionalVideos.LOGGER.debug("transition '{}' ignored: single-entry playlist with no playlistLoop.",
                        declaredTransition);
            }
        }

        Runnable playlistEnd = onCloseCallback == null ? () -> {} : onCloseCallback;
        minecraft.setScreen(new VideoPlaybackScreen(
                config,
                conditionConfig,
                playlist,
                sourceResolver,
                firstSourceUri,
                playlistEnd
        ));
    }

    private static boolean isDownloadPending(Minecraft minecraft, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return false;
        }
        if (VideoSourceResolver.looksLikeUrl(configuredPath)) {
            return false;
        }
        return ActiveConfigResolver.isMultiplayerSession(minecraft)
                && ActiveConfigResolver.isVideoPathInManifest(configuredPath);
    }

    private static URI resolveSource(Minecraft minecraft, String configuredPath, String logName) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }
        if (VideoSourceResolver.looksLikeUrl(configuredPath)) {
            URI uri = VideoSourceResolver.parseUri(configuredPath);
            if (uri == null) {
                ConditionalVideos.LOGGER.warn("Invalid URL '{}' in {} condition. Ignoring.", configuredPath, logName);
            }
            return uri;
        }

        Path resolvedPath = ActiveConfigResolver.resolveRemoteVideoPath(configuredPath);
        if (resolvedPath == null) {
            resolvedPath = VideoPathResolver.resolve(minecraft.gameDirectory.toPath(), configuredPath);
        }
        return resolvedPath == null ? null : resolvedPath.toUri();
    }

    private static boolean shouldGiveUpOnSource(String configuredPath, String logName) {
        if (VideoSourceResolver.looksLikeUrl(configuredPath)) {
            return true;
        }
        ConditionalVideos.LOGGER.warn("Configured {} video '{}' is invalid or not found. Ignoring.", logName, configuredPath);
        return true;
    }
}
