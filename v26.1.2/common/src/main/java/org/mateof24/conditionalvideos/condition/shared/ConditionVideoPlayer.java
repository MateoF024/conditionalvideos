package org.mateof24.conditionalvideos.condition.shared;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.VideoEntry;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.runtime.WaitingRoomClient;
import org.mateof24.conditionalvideos.world.PlaybackSessions;
import org.mateof24.conditionalvideos.video.SilentVideoPreparation;
import org.mateof24.conditionalvideos.video.SyncPoint;
import org.mateof24.conditionalvideos.video.VideoLoadingScreen;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;
import org.mateof24.conditionalvideos.video.backend.WaterMediaVideoBackend;
import org.mateof24.conditionalvideos.video.path.VideoPathResolver;
import org.mateof24.conditionalvideos.video.path.VideoSourceResolver;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    // Conditions waiting for their conditionDelay, in the order they fired.
    private static final Map<String, DelayedPlayback> DELAYED = new LinkedHashMap<>();
    // The condition getting its first video ready with nothing on screen (disableLoadingScreen).
    private static PreparingPlayback preparing;
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
                && (preparing != null || WaitingRoomClient.isActive() || minecraft.screen instanceof VideoPlaybackScreen
                || minecraft.screen instanceof VideoLoadingScreen);
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
        if (FIRST_JOIN_CONDITION_ID.equals(item.conditionId())) {
            QUEUE.addFirst(item);
        } else {
            QUEUE.addLast(item);
        }
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
                    next.conditionId(), next.logName(), next.onCloseCallback(), next.force(), true, null);
            if (started) {
                return;
            }
        }
    }

    // Runs out the delays while the game is not paused. firstJoin goes first when its delay ends.
    public static void tickDelays(Minecraft minecraft) {
        if (DELAYED.isEmpty() || minecraft.isPaused()) {
            return;
        }
        List<DelayedPlayback> due = new ArrayList<>();
        DELAYED.values().removeIf(delayed -> {
            if (--delayed.ticksLeft > 0) {
                return false;
            }
            due.add(delayed);
            return true;
        });
        for (DelayedPlayback delayed : due) {
            QueuedPlayback item = delayed.item;
            DebugLog.client(DebugLog.Area.SCHEDULE, "The delay of '{}' ran out.", item.conditionId());
            if (!play(minecraft, item.config(), item.conditionConfig(), item.conditionId(), item.logName(), item.onCloseCallback(), false, true, null)) {
                PlaybackControlNetworking.notifyPlayback(item.conditionId(), PlaybackSessions.Phase.ENDED, false, false);
            }
        }
    }

    // Advances the silent preparation; the video appears as soon as it has a picture.
    public static void tickPreparation(Minecraft minecraft) {
        if (preparing == null) {
            return;
        }
        PreparingPlayback current = preparing;
        QueuedPlayback item = current.item();
        switch (current.preparation().tick(minecraft.isPaused())) {
            case WAITING -> { }
            case READY -> {
                preparing = null;
                startPlayback(minecraft, item.config(), item.conditionConfig(), item.conditionId(), current.playlist(), current.sourceResolver(),
                        current.preparation().backend().source(), item.onCloseCallback(), item.force(), current.preparation().backend(), current.preparation().index(), null);
            }
            case FAILED -> {
                preparing = null;
                current.preparation().cancel();
                DebugLog.client(DebugLog.Area.PLAYLIST, "Discarded '{}': no video could be prepared.", item.conditionId());
                PlaybackControlNetworking.notifyPlayback(item.conditionId(), PlaybackSessions.Phase.ENDED, false, false);
                if (item.onCloseCallback() != null) {
                    item.onCloseCallback().run();
                }
                playNextInQueue(minecraft);
            }
        }
    }

    private static void cancelPreparation() {
        if (preparing != null) {
            DebugLog.client(DebugLog.Area.PLAYLIST, "Cancelled the silent preparation of '{}'.", preparing.item().conditionId());
            preparing.preparation().cancel();
            PlaybackControlNetworking.notifyPlayback(preparing.item().conditionId(), PlaybackSessions.Phase.ENDED, false, false);
            preparing = null;
        }
    }

    public static void clearQueue() {
        cancelPreparation();
        if (!QUEUE.isEmpty() || !DELAYED.isEmpty()) {
            DebugLog.client(DebugLog.Area.QUEUE, "Cleared {} queued and {} delayed condition(s).", QUEUE.size(), DELAYED.size());
        }
        for (QueuedPlayback queued : QUEUE) {
            if (!queued.force()) {
                PlaybackControlNetworking.notifyPlayback(queued.conditionId(), PlaybackSessions.Phase.ENDED, false, false);
            }
        }
        for (String conditionId : DELAYED.keySet()) {
            PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.ENDED, false, false);
        }
        QUEUE.clear();
        DELAYED.clear();
        LOGGED_RESOLUTIONS.clear();
    }

    public static boolean play(Minecraft minecraft,
                               ConditionalVideosConfig config,
                               ConditionalVideosConfig.ConditionConfig conditionConfig,
                               String conditionId,
                               String logName,
                               Runnable onCloseCallback) {
        return play(minecraft, config, conditionConfig, conditionId, logName, onCloseCallback, false, false, null);
    }

    public static boolean playForced(Minecraft minecraft,
                                     ConditionalVideosConfig config,
                                     ConditionalVideosConfig.ConditionConfig conditionConfig,
                                     String conditionId,
                                     String logName) {
        return play(minecraft, config, conditionConfig, conditionId, logName, null, true, true, null);
    }

    // An order to join others at the point they are.
    public static boolean playForcedAt(Minecraft minecraft,
                                       ConditionalVideosConfig config,
                                       ConditionalVideosConfig.ConditionConfig conditionConfig,
                                       String conditionId,
                                       String logName,
                                       SyncPoint syncPoint) {
        return play(minecraft, config, conditionConfig, conditionId, logName, null, true, true, syncPoint);
    }

    private static boolean play(Minecraft minecraft,
                                ConditionalVideosConfig config,
                                ConditionalVideosConfig.ConditionConfig conditionConfig,
                                String conditionId,
                                String logName,
                                Runnable onCloseCallback,
                                boolean force,
                                boolean delayDone,
                                SyncPoint requestedSync) {
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

        if (!force && ActiveConfigResolver.isExcluded(conditionId)) {
            DebugLog.client(DebugLog.Area.CONDITION, "{} ('{}') does not play: the server left this player out of it.", logName, conditionId);
            return false;
        }

        int delayTicks = conditionConfig.resolvedDelayTicks();
        if (!delayDone && delayTicks > 0) {
            if (DELAYED.containsKey(conditionId)) {
                DebugLog.client(DebugLog.Area.SCHEDULE, "'{}' is already waiting for its delay; ignoring the repeat.", conditionId);
                return true;
            }
            DELAYED.put(conditionId, new DelayedPlayback(new QueuedPlayback(config, conditionConfig, conditionId, logName, onCloseCallback, false), delayTicks));
            PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.DELAYED, true, false);
            DebugLog.client(DebugLog.Area.SCHEDULE, "{} ('{}') plays in {} ticks.", logName, conditionId, delayTicks);
            return true;
        }

        boolean isFirstJoin = FIRST_JOIN_CONDITION_ID.equals(conditionId);
        if (firstJoinGuardActive && !isFirstJoin) {
            enqueue(new QueuedPlayback(config, conditionConfig, conditionId, logName, onCloseCallback, force), "the firstJoin guard");
            return true;
        }

        if (preparing != null && preparing.item().conditionId().equals(conditionId)) {
            DebugLog.client(DebugLog.Area.QUEUE, "'{}' is already being prepared; ignoring the repeat.", conditionId);
            return true;
        }

        if (isVideoActive(minecraft)) {
            enqueue(new QueuedPlayback(config, conditionConfig, conditionId, logName, onCloseCallback, force), "the active video");
            return true;
        }

        DebugLog.client(DebugLog.Area.CONDITION, "{} ('{}') accepted{}; {} video(s) to play.", logName, conditionId,
                force ? " (forced, session gate skipped)" : "", playlist.size());
        Function<VideoEntry, URI> sourceResolver = entry -> resolveSource(minecraft, entry.source(), logName);

        SyncPoint syncPoint = requestedSync != null && requestedSync.entry() >= 0 && requestedSync.entry() < playlist.size() ? requestedSync : null;
        int firstIndex = syncPoint == null ? 0 : syncPoint.entry();
        VideoEntry firstEntry = playlist.get(firstIndex);
        URI firstSourceUri = sourceResolver.apply(firstEntry);
        if (conditionConfig.resolvedDisableLoadingScreen() && (firstSourceUri != null || isDownloadPending(minecraft, firstEntry.source()))) {
            preparing = new PreparingPlayback(new QueuedPlayback(config, conditionConfig, conditionId, logName, onCloseCallback, force), playlist, sourceResolver,
                    new SilentVideoPreparation(playlist, sourceResolver, path -> isDownloadPending(minecraft, path), LOADING_SCREEN_TIMEOUT_TICKS, syncPoint));
            PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.DELAYED, !force, false);
            DebugLog.client(DebugLog.Area.CONDITION, "{} ('{}') prepares its video without a loading screen.", logName, conditionId);
            return true;
        }
        if (firstSourceUri == null) {
            String configuredPath = firstEntry.source();
            if (isDownloadPending(minecraft, configuredPath)) {
                if (minecraft.screen instanceof VideoLoadingScreen) {
                    DebugLog.client(DebugLog.Area.TRANSFER, "'{}' is still downloading and a loading screen is already open.", configuredPath);
                    return true;
                }
                openLoadingScreen(minecraft, config, conditionConfig, conditionId,
                        playlist, sourceResolver, firstIndex, syncPoint, configuredPath, logName, onCloseCallback, force);
                return true;
            }
            PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.ENDED, false, false);
            return shouldGiveUpOnSource(configuredPath, logName);
        }

        startPlayback(minecraft, config, conditionConfig, conditionId,
                playlist, sourceResolver, firstSourceUri, onCloseCallback, force, null, firstIndex, syncPoint);
        return true;
    }

    private static void openLoadingScreen(Minecraft minecraft,
                                          ConditionalVideosConfig config,
                                          ConditionalVideosConfig.ConditionConfig conditionConfig,
                                          String conditionId,
                                          List<VideoEntry> playlist,
                                          Function<VideoEntry, URI> sourceResolver,
                                          int firstIndex,
                                          SyncPoint syncPoint,
                                          String configuredPath,
                                          String logName,
                                          Runnable onCloseCallback,
                                          boolean force) {
        DebugLog.client(DebugLog.Area.TRANSFER, "Opened the loading screen for {}: waiting for '{}' from the server.", logName, configuredPath);
        VideoEntry firstEntry = playlist.get(firstIndex);
        VideoLoadingScreen screen = new VideoLoadingScreen(
                () -> ActiveConfigResolver.resolveRemoteVideoPath(configuredPath) != null,
                () -> {
                    DebugLog.client(DebugLog.Area.TRANSFER, "'{}' arrived from the server; starting {}.", configuredPath, logName);
                    URI readyUri = sourceResolver.apply(firstEntry);
                    if (readyUri == null) {
                        ConditionalVideos.LOGGER.warn("{} video '{}' was reported ready but its URI could not be resolved.",
                                logName, configuredPath);
                        PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.ENDED, false, false);
                        if (minecraft.screen instanceof VideoLoadingScreen) {
                            minecraft.setScreen(null);
                        }
                        if (onCloseCallback != null) {
                            onCloseCallback.run();
                        }
                        playNextInQueue(minecraft);
                        return;
                    }
                    startPlayback(minecraft, config, conditionConfig, conditionId,
                            playlist, sourceResolver, readyUri, onCloseCallback, force, null, firstIndex, syncPoint);
                },
                () -> {
                    ConditionalVideos.LOGGER.warn("Timed out waiting for {} video '{}'.", logName, configuredPath);
                    PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.ENDED, false, false);
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
        PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.LOADING, !force, conditionConfig.resolvedProtectPlayer());
        minecraft.setScreen(screen);
    }

    private static void startPlayback(Minecraft minecraft,
                                      ConditionalVideosConfig config,
                                      ConditionalVideosConfig.ConditionConfig conditionConfig,
                                      String conditionId,
                                      List<VideoEntry> playlist,
                                      Function<VideoEntry, URI> sourceResolver,
                                      URI firstSourceUri,
                                      Runnable onCloseCallback,
                                      boolean force,
                                      WaterMediaVideoBackend preparedBackend,
                                      int startIndex,
                                      SyncPoint syncPoint) {
        boolean once = !force && !conditionConfig.repeatableInSameSession();
        if (once) {
            ActiveConfigResolver.markWatched(conditionId);
            DebugLog.client(DebugLog.Area.CONDITION, "Marked '{}' as watched in this world{}.", conditionId,
                    ActiveConfigResolver.watchedFromServer() ? "" : " (for this connection only: the server keeps no world data)");
        }
        PlaybackControlNetworking.notifyPlayback(conditionId, PlaybackSessions.Phase.STARTED, !force, conditionConfig.resolvedProtectPlayer());

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
        minecraft.setScreen(new VideoPlaybackScreen(
                config,
                conditionConfig,
                playlist,
                sourceResolver,
                firstSourceUri,
                conditionId,
                playlistEnd,
                preparedBackend,
                startIndex,
                syncPoint
        ));
        org.mateof24.conditionalvideos.runtime.PlaybackEvents.notifyStarted(conditionId);
    }

    public static Function<VideoEntry, URI> sourceResolver(Minecraft minecraft, String logName) {
        return entry -> resolveSource(minecraft, entry.source(), logName);
    }

    public static URI backgroundSource(Minecraft minecraft, String configuredPath) {
        return configuredPath == null || configuredPath.isBlank() ? null : resolveSource(minecraft, configuredPath, "waiting room background");
    }

    public static int downloadTimeoutTicks() {
        return LOADING_SCREEN_TIMEOUT_TICKS;
    }

    public static boolean isDownloadPending(Minecraft minecraft, String configuredPath) {
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

    private record PreparingPlayback(QueuedPlayback item, List<VideoEntry> playlist, Function<VideoEntry, URI> sourceResolver, SilentVideoPreparation preparation) {
    }

    private static final class DelayedPlayback {
        private final QueuedPlayback item;
        private int ticksLeft;

        private DelayedPlayback(QueuedPlayback item, int ticksLeft) {
            this.item = item;
            this.ticksLeft = ticksLeft;
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
