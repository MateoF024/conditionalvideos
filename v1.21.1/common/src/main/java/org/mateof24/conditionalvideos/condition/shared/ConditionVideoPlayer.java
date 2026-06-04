package org.mateof24.conditionalvideos.condition.shared;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.join.SessionKeyResolver;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.VideoEntry;
import org.mateof24.conditionalvideos.config.MatureContentFilter;
import org.mateof24.conditionalvideos.video.VideoLoadingScreen;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;
import org.mateof24.conditionalvideos.video.path.VideoPathResolver;
import org.mateof24.conditionalvideos.video.path.VideoSourceResolver;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

public final class ConditionVideoPlayer {
    private static final int LOADING_SCREEN_TIMEOUT_TICKS = 20 * 60;
    private static final int MAX_QUEUE_SIZE = 32;
    private static final String FIRST_JOIN_CONDITION_ID = "firstJoin";

    private static final Deque<QueuedPlayback> QUEUE = new ArrayDeque<>();

    // While armed, any non-firstJoin condition that fires is postponed instead of started, so the
    // firstJoin video is always guaranteed to play first. Recipes/advancements granted by the game
    // itself during world load (e.g. the crafting table recipe on a brand-new world) would otherwise
    // win the race and play before firstJoin. Armed at session start and released once the join flow
    // has resolved (firstJoin started, or determined to have nothing to play).
    private static volatile boolean firstJoinGuardActive = true;

    private ConditionVideoPlayer() {
    }

    public static void armFirstJoinGuard() {
        firstJoinGuardActive = true;
    }

    public static void releaseFirstJoinGuard(Minecraft minecraft) {
        if (!firstJoinGuardActive) {
            return;
        }
        firstJoinGuardActive = false;
        playNextInQueue(minecraft);
    }

    private record QueuedPlayback(
            ConditionalVideosConfig config,
            ConditionalVideosConfig.ConditionConfig conditionConfig,
            String conditionId,
            String logName,
            Runnable onCloseCallback,
            boolean force
    ) {
    }

    private static boolean isVideoActive(Minecraft minecraft) {
        return minecraft != null
                && (minecraft.screen instanceof VideoPlaybackScreen
                || minecraft.screen instanceof VideoLoadingScreen);
    }

    private static void enqueue(QueuedPlayback item) {
        for (QueuedPlayback queued : QUEUE) {
            if (queued.conditionId().equals(item.conditionId())) {
                return;
            }
        }
        if (QUEUE.size() >= MAX_QUEUE_SIZE) {
            ConditionalVideos.LOGGER.debug("Playback queue full ({}); dropping '{}'.", MAX_QUEUE_SIZE, item.conditionId());
            return;
        }
        QUEUE.addLast(item);
        ConditionalVideos.LOGGER.debug("Postponed '{}' behind the active video ({} queued).", item.conditionId(), QUEUE.size());
    }

    public static void playNextInQueue(Minecraft minecraft) {
        if (minecraft == null) {
            return;
        }
        while (!isVideoActive(minecraft)) {
            QueuedPlayback next = QUEUE.pollFirst();
            if (next == null) {
                return;
            }
            boolean started = play(minecraft, next.config(), next.conditionConfig(),
                    next.conditionId(), next.logName(), next.onCloseCallback(), next.force());
            if (started) {
                return;
            }
        }
    }

    public static void clearQueue() {
        QUEUE.clear();
    }

    public static boolean play(Minecraft minecraft,
                               ConditionalVideosConfig config,
                               ConditionalVideosConfig.ConditionConfig conditionConfig,
                               String conditionId,
                               String logName,
                               Runnable onCloseCallback) {
        return play(minecraft, config, conditionConfig, conditionId, logName, onCloseCallback, false);
    }

    public static boolean playForced(Minecraft minecraft,
                                     ConditionalVideosConfig config,
                                     ConditionalVideosConfig.ConditionConfig conditionConfig,
                                     String conditionId,
                                     String logName) {
        return play(minecraft, config, conditionConfig, conditionId, logName, null, true);
    }

    private static boolean play(Minecraft minecraft,
                                ConditionalVideosConfig config,
                                ConditionalVideosConfig.ConditionConfig conditionConfig,
                                String conditionId,
                                String logName,
                                Runnable onCloseCallback,
                                boolean force) {
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
        if (!force && !conditionConfig.repeatableInSameSession()) {
            sessionKey = SessionKeyResolver.resolveSessionKey(minecraft);
            if (config.hasConsumedConditionSession(conditionId, sessionKey)) {
                ConditionalVideos.LOGGER.debug("{} condition '{}' already consumed for session {}.", logName, conditionId, sessionKey);
                return false;
            }
        }

        boolean isFirstJoin = FIRST_JOIN_CONDITION_ID.equals(conditionId);
        if (firstJoinGuardActive && !isFirstJoin) {
            enqueue(new QueuedPlayback(config, conditionConfig, conditionId, logName, onCloseCallback, force));
            return true;
        }

        if (isVideoActive(minecraft)) {
            enqueue(new QueuedPlayback(config, conditionConfig, conditionId, logName, onCloseCallback, force));
            return true;
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
                        playlist, sourceResolver, configuredPath, logName, onCloseCallback, force);
                return true;
            }
            return shouldGiveUpOnSource(configuredPath, logName);
        }

        startPlayback(minecraft, config, conditionConfig, conditionId, sessionKey,
                playlist, sourceResolver, firstSourceUri, onCloseCallback, force);
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
                                          Runnable onCloseCallback,
                                          boolean force) {
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
                        playNextInQueue(minecraft);
                        return;
                    }
                    startPlayback(minecraft, config, conditionConfig, conditionId, sessionKey,
                            playlist, sourceResolver, readyUri, onCloseCallback, force);
                },
                () -> {
                    ConditionalVideos.LOGGER.warn("Timed out waiting for {} video '{}'.", logName, configuredPath);
                    if (minecraft.screen instanceof VideoLoadingScreen) {
                        minecraft.setScreen(null);
                    }
                    if (onCloseCallback != null) {
                        onCloseCallback.run();
                    }
                    playNextInQueue(minecraft);
                },
                LOADING_SCREEN_TIMEOUT_TICKS,
                playlist.size()
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
                                      Runnable onCloseCallback,
                                      boolean force) {
        if (!force && !conditionConfig.repeatableInSameSession()) {
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
                conditionId,
                playlistEnd
        ));
        org.mateof24.conditionalvideos.runtime.PlaybackEvents.notifyStarted(conditionId);
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
                return null;
            }
            if (ActiveConfigResolver.effectiveBlockMatureContent() && MatureContentFilter.isMatureUrl(uri)) {
                ConditionalVideos.LOGGER.warn("Blocked mature-content URL '{}' in {} condition (blockMatureContent=true).", configuredPath, logName);
                return null;
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
