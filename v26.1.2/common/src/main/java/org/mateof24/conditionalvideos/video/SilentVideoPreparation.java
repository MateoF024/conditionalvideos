package org.mateof24.conditionalvideos.video;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.VideoEntry;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.video.backend.WaterMediaVideoBackend;

import java.net.URI;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

// disableLoadingScreen: readies the first video of a condition with nothing on screen while the game goes on.
// It waits for the server's copy if that is still downloading, then runs the player muted until the clock has
// moved, because a paused player or a new texture holds no picture yet. An entry that fails gives way to the
// next one, as in a playing playlist.
public final class SilentVideoPreparation {
    public enum Result { WAITING, READY, FAILED }

    private final List<VideoEntry> playlist;
    private final Function<VideoEntry, URI> sourceResolver;
    private final Predicate<String> downloadPending;
    private final int downloadTimeoutTicks;
    private final float defaultVolume;

    private int index;
    private VideoEntry entry;
    private WaterMediaVideoBackend backend;
    private int elapsedTicks;
    private int downloadTicks;
    private int idleTicks;
    private boolean heldForPause;

    public SilentVideoPreparation(List<VideoEntry> playlist, Function<VideoEntry, URI> sourceResolver,
                                  Predicate<String> downloadPending, int downloadTimeoutTicks) {
        this.playlist = playlist;
        this.sourceResolver = sourceResolver;
        this.downloadPending = downloadPending;
        this.downloadTimeoutTicks = downloadTimeoutTicks;
        this.defaultVolume = ActiveConfigResolver.effectiveDefaultVolume(Minecraft.getInstance());
    }

    // A game paused while the video is ready holds it there, so the pause does not eat into the video.
    public Result tick(boolean gamePaused) {
        elapsedTicks++;
        if (backend == null) {
            Result opened = openEntry();
            if (opened != null) {
                return opened;
            }
        }
        backend.tick();
        if (backend.hasError()) {
            return nextEntry("it reported an error");
        }
        if (backend.hasTextureValid() && backend.positionSeconds() >= entry.resolvedStartAt() + VideoPlaybackScreen.TRAILING_RELEASE_POSITION_SECONDS) {
            if (gamePaused) {
                if (!heldForPause) {
                    heldForPause = true;
                    backend.pause();
                }
                return Result.WAITING;
            }
            if (heldForPause) {
                heldForPause = false;
                backend.resume();
            }
            DebugLog.client(DebugLog.Area.PLAYLIST, "Entry {} ('{}') has a picture after {} ticks of silent preparation.", index, entry.source(), elapsedTicks);
            return Result.READY;
        }
        if (backend.isActivelyLoading()) {
            idleTicks = 0;
            return Result.WAITING;
        }
        if (++idleTicks >= ActiveConfigResolver.effectiveVideoLoadTimeoutSeconds() * 20) {
            return nextEntry("it showed no picture and no loading activity");
        }
        return Result.WAITING;
    }

    // Null once there is a backend to tick.
    private Result openEntry() {
        while (index < playlist.size()) {
            entry = playlist.get(index);
            URI uri = sourceResolver.apply(entry);
            if (uri != null) {
                backend = new WaterMediaVideoBackend(uri, entry.resolvedVolume(defaultVolume));
                backend.setForcedQuality(VideoPlaybackScreen.resolveForcedQuality(ActiveConfigResolver.effectiveVideoQuality(Minecraft.getInstance())));
                backend.setVolumeMultiplier(0f);
                backend.setStartAt(entry.resolvedStartAt());
                idleTicks = 0;
                try {
                    backend.init();
                } catch (Throwable throwable) {
                    ConditionalVideos.LOGGER.warn("Failed to init backend for '{}': {}", backend.source(), throwable.toString());
                }
                DebugLog.client(DebugLog.Area.PLAYLIST, "Preparing entry {} ('{}') with nothing on screen.", index, entry.source());
                return null;
            }
            if (index == 0) {
                if (downloadPending.test(entry.source()) && ++downloadTicks < downloadTimeoutTicks) {
                    return Result.WAITING;
                }
                ConditionalVideos.LOGGER.warn("Timed out waiting for video '{}' from the server.", entry.source());
                return Result.FAILED;
            }
            DebugLog.client(DebugLog.Area.PLAYLIST, "Entry {} ('{}') is not available; trying the next one.", index, entry.source());
            index++;
        }
        return Result.FAILED;
    }

    private Result nextEntry(String reason) {
        ConditionalVideos.LOGGER.warn("Playlist entry '{}' was discarded during its silent preparation: {}.", entry.source(), reason);
        cancel();
        index++;
        return index < playlist.size() ? Result.WAITING : Result.FAILED;
    }

    public void cancel() {
        if (backend != null) {
            try {
                backend.close();
            } catch (Throwable ignored) {
            }
            backend = null;
        }
    }

    public WaterMediaVideoBackend backend() {
        return backend;
    }

    public int index() {
        return index;
    }
}
