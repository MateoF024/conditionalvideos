package org.mateof24.conditionalvideos.condition.shared;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.VideoEntry;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.world.PlaybackSessions;
import org.mateof24.conditionalvideos.video.VideoLoadingScreen;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;
import org.mateof24.conditionalvideos.video.path.VideoPathResolver;
import org.mateof24.conditionalvideos.video.path.VideoSourceResolver;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

// Entry point for every condition: resolves the source, enforces the firstJoin-first guard, queues
// overlapping requests one-at-a-time, and opens the loading/playback screen.
public final class ConditionVideoPlayer {
    private static final int LOADING_SCREEN_TIMEOUT_TICKS = 20 * 60;
    private static final int MAX_QUEUE_SIZE = 32;
    private static final String FIRST_JOIN_CONDITION_ID = "firstJoin";

    private static final Deque<QueuedPlayback> QUEUE = new ArrayDeque<>();
    // Last logged resolution per configured source, so a source re-resolved every tick logs only on change.
    private static final Map<String, String> LOGGED_RESOLUTIONS = new HashMap<>();

    // While armed, any non-firstJoin condition that fires is postponed instead of started, so the
    // firstJoin video is always guaranteed to play first. Recipes/advancements granted by the game
    // itself during world load (e.g. the crafting table recipe on a brand-new world) would otherwise
    // win the race and play before firstJoin. Armed at session start and released once the join flow
    // has resolved (firstJoin started, or determined to have nothing to play).
    private static volatile boolean firstJoinGuardActive = true;

    private ConditionVideoPlayer() {
    }

    public static void armFirstJoinGuard() {
        if (!firstJoinGuardActive) {
            DebugLog.client(DebugLog.Area.QUEUE, "firstJoin guard armed: other conditions wait until the join flow resolves.");
        }
        firstJoinGuardActive = true;
    }

    public static void releaseFirstJoinGuard(Minecraft minecraft) {
        if (!firstJoinGuardActive) {
            return;
        }
        firstJoinGuardActive = false;
        DebugLog.client(DebugLog.Area.QUEUE, "firstJoin guard released; {} queued condition(s) may start.", QUEUE.size());
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

    public static boolean isVideoActive(Minecraft minecraft) {
        return minecraft != null
                && (minecraft.gui.screen() instanceof VideoPlaybackScreen
                || minecraft.gui.screen() instanceof VideoLoadingScreen);
    }

    private static void enqueue(QueuedPlayback item, String reason) {
        for (QueuedPlayback queued : QUEUE) {
            if (queued.conditionId().equals(item.conditionId())) {
                DebugLog.client(DebugLog.Area.QUEUE, "'{}' is already queued; ignoring the repeat.", item.conditionId());
                return;
            }
        }
        if (QUEUE.size() >= MAX_QUEUE_SIZE) {
            DebugLog.client(DebugLog.Area.QUEUE, "Playback queue full ({}); dropping '{}'.", MAX_QUEUE_SIZE, item.conditionId());
            return;
        }
        QUEUE.addLast(item);
        DebugLog.client(DebugLog.Area.QUEUE, "Postponed '{}' behind {} ({} queued).", item.conditionId(), reason, QUEUE.size());
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
            DebugLog.client(DebugLog.Area.QUEUE, "Starting queued '{}' ({} left).", next.conditionId(), QUEUE.size());
            boolean started = play(minecraft, next.config(), next.conditionConfig(),
                    next.conditionId(), next.logName(), next.onCloseCallback(), next.force());
            if (started) {
                return;
            }
        }
    }

    public static void clearQueue() {
        if (!QUEUE.isEmpty()) {
            DebugLog.client(DebugLog.Area.QUEUE, "Cleared {} queued condition(s).", QUEUE.size());
        }
        QUEUE.clear();
        LOGGED_RESOLUTIONS.clear();
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
            DebugLog.client(DebugLog.Area.CONDITION, "No video configured for {} ('{}'); nothing to play.", logName, conditionId);
            return false;
        }

        List<VideoEntry> playlist = conditionConfig.resolvedPlaylist();
        if (playlist.isEmpty()) {
            DebugLog.client(DebugLog.Area.CONDITION, "No video configured for {} ('{}'); nothing to play.", logName, conditionId);
            return false;
        }

        if (!force && !conditionConfig.repeatableInSameSession() && ActiveConfigResolver.hasWatched(conditionId)) {
            DebugLog.client(DebugLog.Area.CONDITION, "{} ('{}') was already watched in this world; not playing it again.",
                    logName, conditionId);
            return false;
        }

        boolean isFirstJoin = FIRST_JOIN_CONDITION_ID.equals(conditionId);
        if (firstJoinGuardActive && !isFirstJoin) {
            enqueue(new QueuedPlayback(config, conditionConfig, conditionId, logName, onCloseCallback, force), "the firstJoin guard");
            return true;
        }

        if (isVideoActive(minecraft)) {
            enqueue(new QueuedPlayback(config, conditionConfig, conditionId, logName, onCloseCallback, force), "the active video");
            return true;
        }

        DebugLog.client(DebugLog.Area.CONDITION, "{} ('{}') accepted{}; {} video(s) to play.", logName, conditionId,
                force ? " (forced, session gate skipped)" : "", playlist.size());
        Function<VideoEntry, URI> sourceResolver = entry -> resolveSource(minecraft, entry.source(), logName);

        VideoEntry firstEntry = playlist.get(0);
        URI firstSourceUri = sourceResolver.apply(firstEntry);
        if (firstSourceUri == null) {
            String configuredPath = firstEntry.source();
            if (isDownloadPending(minecraft, configuredPath)) {
                if (minecraft.gui.screen() instanceof VideoLoadingScreen) {
                    DebugLog.client(DebugLog.Area.TRANSFER, "'{}' is still downloading and a loading screen is already open.", configuredPath);
                    return true;
                }
                openLoadingScreen(minecraft, config, conditionConfig, conditionId,
                        playlist, sourceResolver, configuredPath, logName, onCloseCallback, force);
                return true;
            }
            return shouldGiveUpOnSource(configuredPath, logName);
        }

        startPlayback(minecraft, config, conditionConfig, conditionId,
                playlist, sourceResolver, firstSourceUri, onCloseCallback, force);
        return true;
    }

    private static void openLoadingScreen(Minecraft minecraft,
                                          ConditionalVideosConfig config,
                                          ConditionalVideosConfig.ConditionConfig conditionConfig,
                                          String conditionId,
                                          List<VideoEntry> playlist,
                                          Function<VideoEntry, URI> sourceResolver,
                                          String configuredPath,
                                          String logName,
                                          Runnable onCloseCallback,
                                          boolean force) {
        DebugLog.client(DebugLog.Area.TRANSFER, "Opened the loading screen for {}: waiting for '{}' from the server.", logName, configuredPath);
        VideoEntry firstEntry = playlist.get(0);
        VideoLoadingScreen screen = new VideoLoadingScreen(
                () -> ActiveConfigResolver.resolveRemoteVideoPath(configuredPath) != null,
                () -> {
                    DebugLog.client(DebugLog.Area.TRANSFER, "'{}' arrived from the server; starting {}.", configuredPath, logName);
                    URI readyUri = sourceResolver.apply(firstEntry);
                    if (readyUri == null) {
                        ConditionalVideos.LOGGER.warn("{} video '{}' was reported ready but its URI could not be resolved.",
                                logName, configuredPath);
                        PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.ENDED, false, false);
                        if (minecraft.gui.screen() instanceof VideoLoadingScreen) {
                            minecraft.gui.setScreen(null);
                        }
                        if (onCloseCallback != null) {
                            onCloseCallback.run();
                        }
                        playNextInQueue(minecraft);
                        return;
                    }
                    startPlayback(minecraft, config, conditionConfig, conditionId,
                            playlist, sourceResolver, readyUri, onCloseCallback, force);
                },
                () -> {
                    ConditionalVideos.LOGGER.warn("Timed out waiting for {} video '{}'.", logName, configuredPath);
                    PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.ENDED, false, false);
                    if (minecraft.gui.screen() instanceof VideoLoadingScreen) {
                        minecraft.gui.setScreen(null);
                    }
                    if (onCloseCallback != null) {
                        onCloseCallback.run();
                    }
                    playNextInQueue(minecraft);
                },
                LOADING_SCREEN_TIMEOUT_TICKS,
                playlist.size()
        );
        PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.LOADING, false, conditionConfig.resolvedProtectPlayer());
        minecraft.gui.setScreen(screen);
    }

    private static void startPlayback(Minecraft minecraft,
                                      ConditionalVideosConfig config,
                                      ConditionalVideosConfig.ConditionConfig conditionConfig,
                                      String conditionId,
                                      List<VideoEntry> playlist,
                                      Function<VideoEntry, URI> sourceResolver,
                                      URI firstSourceUri,
                                      Runnable onCloseCallback,
                                      boolean force) {
        boolean once = !force && !conditionConfig.repeatableInSameSession();
        if (once) {
            ActiveConfigResolver.markWatched(conditionId);
            DebugLog.client(DebugLog.Area.CONDITION, "Marked '{}' as watched in this world{}.", conditionId,
                    ActiveConfigResolver.watchedFromServer() ? "" : " (for this connection only: the server keeps no world data)");
        }
        PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.STARTED, once, conditionConfig.resolvedProtectPlayer());

        VideoEntry firstEntry = playlist.get(0);
        if (playlist.size() == 1 && !conditionConfig.resolvedPlaylistLoop()) {
            String declaredTransition = firstEntry.transition();
            if (declaredTransition != null && !declaredTransition.isBlank()) {
                DebugLog.client(DebugLog.Area.PLAYLIST, "Transition '{}' ignored: single-entry playlist with no playlistLoop.",
                        declaredTransition);
            }
        }

        Runnable playlistEnd = () -> {
            PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.ENDED, false, false);
            if (onCloseCallback != null) {
                onCloseCallback.run();
            }
        };
        minecraft.gui.setScreen(new VideoPlaybackScreen(
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
            logResolution("<empty source>", "nothing to resolve");
            return null;
        }
        if (VideoSourceResolver.looksLikeUrl(configuredPath)) {
            URI uri = VideoSourceResolver.parseUri(configuredPath);
            if (uri == null) {
                ConditionalVideos.LOGGER.warn("Invalid URL '{}' in {} condition. Ignoring.", configuredPath, logName);
                return null;
            }
            logResolution(configuredPath, "URL " + uri);
            return uri;
        }

        Path resolvedPath = ActiveConfigResolver.resolveRemoteVideoPath(configuredPath);
        if (resolvedPath != null) {
            logResolution(configuredPath, "copy received from the server '" + resolvedPath + "'");
            return resolvedPath.toUri();
        }
        resolvedPath = VideoPathResolver.resolve(minecraft.gameDirectory.toPath(), configuredPath);
        logResolution(configuredPath, resolvedPath == null ? "not found (no local file, no server copy yet)"
                : "local file '" + resolvedPath + "'");
        return resolvedPath == null ? null : resolvedPath.toUri();
    }

    private static void logResolution(String configuredPath, String result) {
        if (DebugLog.clientEnabled() && !result.equals(LOGGED_RESOLUTIONS.put(configuredPath, result))) {
            DebugLog.client(DebugLog.Area.SOURCE, "Source '{}' -> {}.", configuredPath, result);
        }
    }

    private static boolean shouldGiveUpOnSource(String configuredPath, String logName) {
        if (VideoSourceResolver.looksLikeUrl(configuredPath)) {
            return true;
        }
        ConditionalVideos.LOGGER.warn("Configured {} video '{}' is invalid or not found. Ignoring.", logName, configuredPath);
        return true;
    }
}
