package org.mateof24.conditionalvideos.video.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.debug.ClientDebug;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.VideoDiagnostics;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.GFXEngine;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.MediaPlayer;
import org.watermedia.api.media.SFXEngine;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

// Bridges one video source to a WaterMedia v3 player and renders its texture full-screen.
public final class WaterMediaVideoBackend {
    private static final float DEFAULT_VIDEO_ASPECT_RATIO = 16.0F / 9.0F;
    private static final int MRL_ERROR_GRACE_TICKS = 20 * 30;
    private static final int MRL_RELOAD_SPACING_TICKS = 40;
    private static final int MAX_MRL_RELOAD_ATTEMPTS = 3;
    private static final long HOLD_AT_ZERO_THRESHOLD_MS = 100L;
    private static final int QUALITY_SETTLE_TICKS = 40;

    private final URI source;
    private final float configuredVolume;
    private final VideoDiagnostics diagnostics = new VideoDiagnostics();
    private final AtomicInteger glUploadCount = new AtomicInteger();
    private float volumeMultiplier = 1f;
    private boolean startPaused;
    private boolean resumeOnReady;
    private boolean holdAtZero;
    // startAt: the player stays paused until the source is open, validated and sought there.
    private long startAtMillis;
    private boolean startAtPending;
    private boolean resumeAfterStartAt;

    private MRL mrl;
    private MediaPlayer player;
    private volatile MediaPlayer.Status playerStatus;
    private GFXEngine gfx;
    private SFXEngine sfx;
    private MediaQuality desiredQuality = MediaQuality.HIGHEST;
    private MediaQuality forcedQuality;

    private boolean renderedAnyFrame;
    private boolean closed;
    private boolean errored;
    private boolean playerStarted;
    private int mrlWaitTicks;
    private int mrlErrorTicks;
    private int mrlReloadAttempts;
    private int createPlayerFailTicks;
    private int appliedVolumeIntCache = Integer.MIN_VALUE;
    private MRL.Status lastLoggedStatus;
    private long mrlRequestedNanos;
    private MediaQuality lastRequestedQuality;
    private boolean qualityCeilingReached;
    private int qualitySettleTicks;

    public WaterMediaVideoBackend(URI source, float volume) {
        this.source = source;
        this.configuredVolume = Math.max(0f, Math.min(1f, volume));
    }

    public boolean hasRenderedAnyFrame() {
        return renderedAnyFrame;
    }

    public boolean isReadyToRender() {
        if (player == null || closed) {
            return false;
        }
        try {
            return player.texture() > 0 && (playerStatus == MediaPlayer.Status.PLAYING
                    || playerStatus == MediaPlayer.Status.PAUSED
                    || playerStatus == MediaPlayer.Status.BUFFERING);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean hasTextureValid() {
        if (closed) {
            return false;
        }
        try {
            if (player != null && player.texture() > 0) {
                return true;
            }
            if (gfx != null && gfx.texture() > 0) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public URI source() {
        return source;
    }

    public boolean hasError() {
        return errored;
    }

    // True while still resolving the MRL or buffering, so a slow-but-progressing source is not mistaken
    // for a stall.
    public boolean isActivelyLoading() {
        if (closed || errored) {
            return false;
        }
        if (player != null) {
            return playerStatus == MediaPlayer.Status.LOADING
                    || playerStatus == MediaPlayer.Status.BUFFERING
                    || playerStatus == MediaPlayer.Status.WAITING;
        }
        if (mrl == null) {
            return true;
        }
        MRL.Status status;
        try {
            status = mrl.status();
        } catch (Throwable ignored) {
            return false;
        }
        return status == null || !(status == MRL.Status.ERROR || status == MRL.Status.BLOCKED);
    }

    private int loadTimeoutTicks() {
        return ActiveConfigResolver.effectiveVideoLoadTimeoutSeconds() * 20;
    }

    public boolean isPlayerReady() {
        return playerStarted && player != null && !closed && !errored;
    }

    public void setForcedQuality(MediaQuality quality) {
        this.forcedQuality = quality;
    }

    public void setStartPaused(boolean value) {
        this.startPaused = value;
        this.holdAtZero = value;
    }

    public void setStartAt(float seconds) {
        startAtMillis = Math.round(seconds * 1000d);
        startAtPending = startAtMillis > 0L;
    }

    public void resumePlayback() {
        if (closed) {
            return;
        }
        holdAtZero = false;
        if (player == null) {
            resumeOnReady = true;
            return;
        }
        if (startAtPending) {
            resumeAfterStartAt = true;
            return;
        }
        boolean needsSeek = true;
        try {
            long ms = player.time();
            if (ms >= startAtMillis && ms < startAtMillis + 100L) {
                needsSeek = false;
            }
        } catch (Throwable ignored) {
        }
        if (needsSeek) {
            try {
                if (player.canSeek()) {
                    player.seek(startAtMillis);
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            player.resume();
        } catch (Throwable t) {
            try {
                player.start();
            } catch (Throwable t2) {
                DebugLog.client(DebugLog.Area.PLAYBACK, "resume()/start() failed for '{}': {}", source, t2.toString());
            }
        }
    }

    public void pause() {
        if (player == null || closed) {
            return;
        }
        try {
            player.pause();
        } catch (Throwable t) {
            DebugLog.client(DebugLog.Area.PLAYBACK, "pause() failed for '{}': {}", source, t.toString());
        }
    }

    public void resume() {
        if (player == null || closed) {
            return;
        }
        try {
            player.resume();
        } catch (Throwable t) {
            DebugLog.client(DebugLog.Area.PLAYBACK, "resume() failed for '{}': {}", source, t.toString());
        }
    }

    public void init() {
        applyMatureContentPolicy();
        if (!createEngines()) {
            return;
        }
        tryAcquireMrl();
    }

    // Fresh GL/AL engine per source: reusing a player across sources froze the texture (loop-freeze fix).
    private boolean createEngines() {
        try {
            Thread renderThread = Thread.currentThread();
            Executor renderExecutor = (Runnable r) -> RenderSystem.recordRenderCall(() -> {
                if (DebugLog.clientEnabled()) {
                    glUploadCount.incrementAndGet();
                }
                r.run();
            });
            gfx = MediaAPI.glEngine(renderThread, renderExecutor);
            sfx = MediaAPI.alEngine();
            DebugLog.client(DebugLog.Area.BACKEND, "Created the OpenGL video and OpenAL audio engines for '{}'.", source);
            return true;
        } catch (Throwable throwable) {
            ConditionalVideos.LOGGER.warn("Failed to initialize WATERMeDIA v3 engines for '{}': {}", source, throwable.toString());
            errored = true;
            cleanup();
            return false;
        }
    }

    private void applyMatureContentPolicy() {
        try {
            WaterMediaConfig.platforms.allowMatureContent = !ActiveConfigResolver.effectiveBlockMatureContent();
        } catch (Throwable t) {
            DebugLog.client(DebugLog.Area.SOURCE, "Failed to apply mature-content policy to WaterMedia: {}", t.toString());
        }
    }

    private void tryAcquireMrl() {
        if (mrl != null || closed || errored) {
            return;
        }
        try {
            mrl = MediaAPI.mrl(source);
            if (mrl != null && mrl.status() == MRL.Status.EXPIRED) {
                try {
                    mrl = mrl.reload();
                } catch (Throwable t) {
                    DebugLog.client(DebugLog.Area.SOURCE, "MRL.reload() failed for '{}': {}", source, t.toString());
                }
            }
            if (mrl != null) {
                ConditionalVideos.LOGGER.info("Requested MRL load from WATERMeDIA v3: {}", source);
                mrlRequestedNanos = System.nanoTime();
                lastLoggedStatus = MRL.Status.FETCHING;
            }
        } catch (Throwable throwable) {
            DebugLog.client(DebugLog.Area.SOURCE, "MediaAPI.mrl() not ready yet for '{}': {}", source, throwable.toString());
        }
    }

    public void tick() {
        if (closed) {
            return;
        }
        if (player == null) {
            tickPreStart();
            return;
        }
        reinforceQuality();
        tickStartAt();
        enforceHoldAtZero();
    }

    // Re-asserts desiredQuality on multi-variant streams with hysteresis: once the player can't reach the
    // target within QUALITY_SETTLE_TICKS it stops re-requesting, so an unreachable target does not re-open
    // the decoder every tick (first-local-video freeze).
    private void reinforceQuality() {
        if (!playerStarted || player == null || closed
                || desiredQuality == null || desiredQuality == MediaQuality.UNKNOWN) {
            return;
        }
        MediaQuality current;
        try {
            current = player.quality();
        } catch (Throwable ignored) {
            return;
        }
        if (current == null) {
            return;
        }
        boolean satisfied = forcedQuality != null
                ? current == desiredQuality
                : current != MediaQuality.UNKNOWN && current.height() >= desiredQuality.height();
        if (satisfied) {
            qualitySettleTicks = 0;
            lastRequestedQuality = null;
            qualityCeilingReached = false;
            return;
        }
        if (qualityCeilingReached) {
            return;
        }
        if (desiredQuality.equals(lastRequestedQuality)) {
            qualitySettleTicks++;
            if (qualitySettleTicks >= QUALITY_SETTLE_TICKS) {
                qualityCeilingReached = true;
                DebugLog.client(DebugLog.Area.QUALITY, "Quality ceiling for '{}': stuck at {} below target {} after {} ticks; stop re-requesting.", source, current, desiredQuality, QUALITY_SETTLE_TICKS);
            }
            return;
        }
        try {
            player.quality(desiredQuality);
            lastRequestedQuality = desiredQuality;
            qualitySettleTicks = 0;
            DebugLog.client(DebugLog.Area.QUALITY, "Requested quality {} for '{}' (was {}).", desiredQuality, source, current);
        } catch (Throwable ignored) {
        }
    }

    // Once the source is open: a live stream, one that cannot seek or one shorter than startAt cannot honour it,
    // so the entry fails and the playlist moves on. Otherwise it seeks there and resumes if it was meant to play.
    private void tickStartAt() {
        if (!startAtPending || closed) {
            return;
        }
        MediaPlayer.Status status = playerStatus;
        if (status == MediaPlayer.Status.ERROR || status == MediaPlayer.Status.ENDED || status == MediaPlayer.Status.STOPPED) {
            startAtPending = false;
            return;
        }
        if (status != MediaPlayer.Status.PAUSED && status != MediaPlayer.Status.PLAYING && status != MediaPlayer.Status.BUFFERING) {
            return;
        }
        try {
            boolean live = player.live();
            long duration = player.duration();
            if (live || !player.canSeek() || (duration > 0L && startAtMillis >= duration)) {
                ConditionalVideos.LOGGER.warn("Video '{}' cannot start at {} s ({}); skipping it.", source, startAtMillis / 1000d,
                        live ? "it is a live source" : duration > 0L ? "it lasts " + duration / 1000d + " s" : "it cannot seek");
                startAtPending = false;
                errored = true;
                return;
            }
            if (player.seek(startAtMillis)) {
                startAtPending = false;
                DebugLog.client(DebugLog.Area.PLAYBACK, "'{}' starts at {} s.", source, startAtMillis / 1000d);
                if (resumeAfterStartAt) {
                    resumeAfterStartAt = false;
                    player.resume();
                }
            }
        } catch (Throwable throwable) {
            DebugLog.client(DebugLog.Area.PLAYBACK, "Could not seek '{}' to its startAt yet: {}", source, throwable.toString());
        }
    }

    private void enforceHoldAtZero() {
        if (!holdAtZero || !playerStarted || player == null || closed) {
            return;
        }
        try {
            if (playerStatus != MediaPlayer.Status.PLAYING) {
                return;
            }
            try { player.pause(); } catch (Throwable ignored) { }
            try {
                if (player.time() > startAtMillis + HOLD_AT_ZERO_THRESHOLD_MS && player.canSeek()) {
                    player.seek(startAtMillis);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private void tickPreStart() {
        if (playerStarted) {
            return;
        }
        if (mrl == null) {
            mrlWaitTicks++;
            if (mrlWaitTicks >= loadTimeoutTicks()) {
                ConditionalVideos.LOGGER.warn("MediaAPI never produced an MRL for '{}' after {}s.", source, loadTimeoutTicks() / 20);
                errored = true;
                cleanup();
                return;
            }
            tryAcquireMrl();
            return;
        }
        MRL.Status status;
        try {
            status = mrl.status();
        } catch (Throwable ignored) {
            status = null;
        }
        if (status != lastLoggedStatus) {
            lastLoggedStatus = status;
            DebugLog.client(DebugLog.Area.SOURCE, "MRL '{}' -> {} after {} ms.", source, status,
                    (System.nanoTime() - mrlRequestedNanos) / 1_000_000L);
        }
        if (status == MRL.Status.ERROR) {
            if (mrlReloadAttempts >= MAX_MRL_RELOAD_ATTEMPTS) {
                Throwable cause = null;
                try {
                    cause = mrl.exception();
                } catch (Throwable ignored) {
                }
                ConditionalVideos.LOGGER.warn("Source '{}' is invalid or unavailable (could not be resolved after {} attempt(s)); skipping it: {}", source, mrlReloadAttempts, cause != null ? cause.toString() : "unknown");
                errored = true;
                cleanup();
                return;
            }
            mrlErrorTicks++;
            if (mrlErrorTicks >= MRL_RELOAD_SPACING_TICKS) {
                mrlErrorTicks = 0;
                mrlReloadAttempts++;
                DebugLog.client(DebugLog.Area.SOURCE, "Retrying MRL '{}' (attempt {}/{}).", source, mrlReloadAttempts, MAX_MRL_RELOAD_ATTEMPTS);
                try {
                    mrl = mrl.reload();
                } catch (Throwable t) {
                    DebugLog.client(DebugLog.Area.SOURCE, "MRL.reload() failed for '{}': {}", source, t.toString());
                }
            }
            return;
        }
        mrlErrorTicks = 0;
        if (status == MRL.Status.FORGOTTEN) {
            mrl = null;
            tryAcquireMrl();
            return;
        }
        if (status == MRL.Status.EXPIRED) {
            try {
                mrl = mrl.reload();
            } catch (Throwable t) {
                DebugLog.client(DebugLog.Area.SOURCE, "MRL.reload() failed for '{}': {}", source, t.toString());
            }
            mrlWaitTicks++;
            if (mrlWaitTicks >= loadTimeoutTicks()) {
                ConditionalVideos.LOGGER.warn("Timed out waiting for MRL '{}' to resolve.", source);
                errored = true;
                cleanup();
            }
            return;
        }
        if (status == MRL.Status.BLOCKED) {
            ConditionalVideos.LOGGER.warn("MRL '{}' is blocked by WaterMedia (restricted/mature content); skipping.", source);
            errored = true;
            cleanup();
            return;
        }
        if (status != MRL.Status.LOADED) {
            mrlWaitTicks++;
            if (mrlWaitTicks >= loadTimeoutTicks()) {
                ConditionalVideos.LOGGER.warn("Timed out waiting for MRL '{}' to resolve.", source);
                errored = true;
                cleanup();
            }
            return;
        }
        try {
            int videoIndex = resolveVideoSourceIndex();
            MRL.Source preferred = resolveSourceAt(videoIndex);
            if (preferred == null) {
                ConditionalVideos.LOGGER.warn("MRL '{}' resolved but reported no playable source.", source);
                errored = true;
                cleanup();
                return;
            }
            if (preferred.type() == MediaType.VIDEO && ffmpegErrored()) {
                reportFfmpegUnavailable();
                errored = true;
                cleanup();
                return;
            }
            startSourcePlayer(preferred, videoIndex);
        } catch (Throwable throwable) {
            ConditionalVideos.LOGGER.warn("Failed to start WATERMeDIA v3 player for '{}': {}", source, throwable.toString());
            createPlayerFailTicks++;
            if (createPlayerFailTicks >= MRL_ERROR_GRACE_TICKS) {
                errored = true;
                cleanup();
            } else {
                player = null;
            }
        }
    }

    // index >= 0 selects that source explicitly; -1 falls back to WaterMedia's default source. The MRL is
    // already LOADED here, so a null player is a permanent failure (no backend or a construction crash).
    private void startSourcePlayer(MRL.Source preferred, int index) {
        player = index >= 0
                ? MediaAPI.player(mrl, index, () -> gfx, () -> sfx)
                : MediaAPI.player(mrl, () -> gfx, () -> sfx);
        if (player == null) {
            ConditionalVideos.LOGGER.warn("WATERMeDIA could not create a player for '{}'; skipping it.", source);
            errored = true;
            cleanup();
            return;
        }
        registerStatusListener();
        if (ffmpegLoaded()) {
            ClientDebug.applyFfmpegLogLevel();
        }
        desiredQuality = resolveDesiredQuality(preferred, index);
        appliedVolumeIntCache = Integer.MIN_VALUE;
        lastRequestedQuality = null;
        qualityCeilingReached = false;
        qualitySettleTicks = 0;
        applyQualityIfPossible();
        applyVolumeIfPossible();
        boolean holdRequested = startPaused && !resumeOnReady;
        boolean willStartPaused = holdRequested || startAtPending;
        resumeAfterStartAt = startAtPending && !holdRequested;
        if (willStartPaused) {
            try {
                player.startPaused();
            } catch (Throwable t) {
                DebugLog.client(DebugLog.Area.PLAYBACK, "startPaused() failed for '{}', falling back to start()+pause(): {}", source, t.toString());
                try {
                    player.start();
                    player.pause();
                } catch (Throwable t2) {
                    DebugLog.client(DebugLog.Area.PLAYBACK, "start()+pause() fallback failed for '{}': {}", source, t2.toString());
                }
            }
            try {
                if (!startAtPending && player.canSeek()) {
                    player.seek(0L);
                }
            } catch (Throwable ignored) {
            }
        } else {
            holdAtZero = false;
            player.start();
        }
        playerStarted = true;
        resumeOnReady = false;
        ConditionalVideos.LOGGER.info("Started WATERMeDIA v3 player for '{}' (quality={}, startPaused={}, holdAtZero={}).",
                source, player.quality(), willStartPaused, holdAtZero);
    }

    // Caches player status from the onStatus event (seeded once) so per-tick queries read one volatile
    // field instead of several native getters; the callback may run off-thread, hence volatile. A terminal
    // status consumes the listener, which is fine because a finished player is never restarted. Every
    // transition before the release is logged, with WaterMedia's own failure cause when it lands in ERROR.
    private void registerStatusListener() {
        try {
            player.onStatus((p, previous, current) -> {
                playerStatus = current;
                if (DebugLog.clientEnabled() && !closed) {
                    Throwable cause = current == MediaPlayer.Status.ERROR ? p.exception() : null;
                    DebugLog.client(DebugLog.Area.PLAYBACK, "Player '{}': {} -> {}{}.", source, previous, current,
                            cause == null ? "" : " (cause: " + cause + ")");
                }
            });
        } catch (Throwable t) {
            DebugLog.client(DebugLog.Area.PLAYBACK, "onStatus() registration failed for '{}': {}", source, t.toString());
        }
        try {
            playerStatus = player.status();
        } catch (Throwable ignored) {
        }
    }

    private static boolean ffmpegLoaded() {
        try {
            return MediaAPI.ffmpegLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean ffmpegErrored() {
        try {
            return MediaAPI.ffmpegError();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // Surfaces a clear reason when FFmpeg failed to load, instead of a generic timeout.
    private void reportFfmpegUnavailable() {
        ConditionalVideos.LOGGER.warn("Cannot play video '{}': WaterMedia's FFmpeg backend failed to load; "
                + "video playback is unavailable. Verify the WaterMedia and WaterMedia Binaries installation "
                + "(WaterMedia logged the reason at startup).", source);
    }

    // First video source, or -1 for WaterMedia's default. Only one source is played; playlist MRLs are
    // intentionally unsupported (their sequential playback stuttered and froze).
    private int resolveVideoSourceIndex() {
        try {
            int count = mrl.sourceCount();
            for (int i = 0; i < count; i++) {
                MRL.Source candidate = mrl.source(i);
                if (candidate != null && candidate.type() == MediaType.VIDEO) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private MRL.Source resolveSourceAt(int index) {
        try {
            if (index >= 0) {
                MRL.Source at = mrl.source(index);
                if (at != null) {
                    return at;
                }
            }
            MRL.Source video = mrl.sourceByType(MediaType.VIDEO);
            return video != null ? video : mrl.sourceByType(MediaType.IMAGE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Returns null (quality left unmanaged) for single-variant sources (local files, direct mp4): forcing
    // a quality on them re-opens the decoder every tick and freezes the first local video.
    private MediaQuality resolveDesiredQuality(MRL.Source preferred, int index) {
        Set<MediaQuality> available;
        try {
            available = preferred.qualities().keySet();
        } catch (Throwable ignored) {
            available = null;
        }
        if (available == null || available.size() <= 1) {
            DebugLog.client(DebugLog.Area.QUALITY, "Player for '{}' uses source #{} ({}) with a single quality; quality left unmanaged.",
                    source, index, preferred.type());
            return null;
        }
        MediaQuality target = forcedQuality != null ? forcedQuality : MediaQuality.HIGHEST;
        try {
            MediaQuality picked = MediaQuality.closest(available, target);
            if (picked != null && picked != MediaQuality.UNKNOWN) {
                DebugLog.client(DebugLog.Area.QUALITY, "Player for '{}' uses source #{} ({}) with {} qualities; desired {} (target {}).",
                        source, index, preferred.type(), available.size(), picked, target);
                return picked;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void applyQualityIfPossible() {
        if (player == null || closed || desiredQuality == null) {
            return;
        }
        try {
            player.quality(desiredQuality);
        } catch (Throwable t) {
            DebugLog.client(DebugLog.Area.QUALITY, "player.quality({}) failed for '{}': {}", desiredQuality, source, t.toString());
        }
    }

    public void setVolumeMultiplier(float multiplier) {
        float clamped = Math.max(0f, Math.min(1f, multiplier));
        if (Math.abs(clamped - volumeMultiplier) < 0.005f) {
            return;
        }
        volumeMultiplier = clamped;
        applyVolumeIfPossible();
    }

    private void applyVolumeIfPossible() {
        if (player == null) {
            return;
        }
        int target = Math.round(configuredVolume * volumeMultiplier * 100f);
        if (target == appliedVolumeIntCache) {
            return;
        }
        try {
            player.volume(target);
            appliedVolumeIntCache = target;
        } catch (Throwable ignored) {
        }
    }

    public void render(int width, int height) {
        render(width, height, 1f);
    }

    public void render(int width, int height, float alpha) {
        long texId = 0L;
        if (player != null) {
            try {
                texId = player.texture();
            } catch (Throwable ignored) {
                texId = 0L;
            }
        }
        if (texId <= 0 && gfx != null) {
            try {
                texId = gfx.texture();
            } catch (Throwable ignored) {
                texId = 0L;
            }
        }
        if (texId <= 0) {
            return;
        }
        renderedAnyFrame = true;
        diagnostics.sample(source, player, gfx, texId, glUploadCount);

        RenderBounds bounds = calculateRenderBounds(width, height);
        float clampedAlpha = Math.max(0f, Math.min(1f, alpha));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, clampedAlpha);
        RenderSystem._setShaderTexture(0, (int) texId);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(bounds.left, bounds.bottom, 0).uv(0f, 1f).endVertex();
        builder.vertex(bounds.right, bounds.bottom, 0).uv(1f, 1f).endVertex();
        builder.vertex(bounds.right, bounds.top, 0).uv(1f, 0f).endVertex();
        builder.vertex(bounds.left, bounds.top, 0).uv(0f, 0f).endVertex();
        BufferUploader.drawWithShader(builder.end());

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    public void close() {
        cleanup();
    }

    // Releases the player and both engines. The engines are released here as well because a source that
    // never got a player still owns them (the AL engine holds an OpenAL source from construction).
    private void cleanup() {
        if (!closed) {
            DebugLog.client(DebugLog.Area.BACKEND, "Releasing the backend for '{}' (frames shown: {}, failed: {}).",
                    source, renderedAnyFrame, errored);
        }
        closed = true;
        if (player != null) {
            try {
                player.mute(true);
            } catch (Throwable ignored) {
            }
            try {
                player.stop();
            } catch (Throwable ignored) {
            }
            try {
                player.release();
            } catch (Throwable t) {
                DebugLog.client(DebugLog.Area.BACKEND, "Failed to release player for '{}': {}", source, t.toString());
            }
            player = null;
        }
        if (gfx != null) {
            try {
                gfx.release();
            } catch (Throwable t) {
                DebugLog.client(DebugLog.Area.BACKEND, "Failed to release GFX engine for '{}': {}", source, t.toString());
            }
            gfx = null;
        }
        if (sfx != null) {
            try {
                sfx.release();
            } catch (Throwable t) {
                DebugLog.client(DebugLog.Area.BACKEND, "Failed to release SFX engine for '{}': {}", source, t.toString());
            }
            sfx = null;
        }
        mrl = null;
    }

    public boolean hasFinished() {
        if (closed) {
            return true;
        }
        if (player == null) {
            return false;
        }
        try {
            return playerStatus == MediaPlayer.Status.ERROR
                    || playerStatus == MediaPlayer.Status.ENDED
                    || playerStatus == MediaPlayer.Status.STOPPED;
        } catch (Throwable t) {
            return false;
        }
    }

    public double positionSeconds() {
        if (player == null || closed) {
            return -1d;
        }
        try {
            long ms = player.time();
            if (ms < 0L) {
                return -1d;
            }
            return ms / 1000d;
        } catch (Throwable t) {
            return -1d;
        }
    }

    public double durationSeconds() {
        if (player == null || closed) {
            return -1d;
        }
        try {
            long ms = player.duration();
            if (ms <= 0L) {
                return -1d;
            }
            return ms / 1000d;
        } catch (Throwable t) {
            return -1d;
        }
    }

    private RenderBounds calculateRenderBounds(int screenWidth, int screenHeight) {
        float videoAspect = resolveVideoAspectRatio();
        float screenAspect = (float) screenWidth / (float) screenHeight;

        int drawWidth = screenWidth;
        int drawHeight = screenHeight;
        int left = 0;
        int top = 0;

        if (screenAspect > videoAspect) {
            drawWidth = Math.round(screenHeight * videoAspect);
            left = (screenWidth - drawWidth) / 2;
        } else if (screenAspect < videoAspect) {
            drawHeight = Math.round(screenWidth / videoAspect);
            top = (screenHeight - drawHeight) / 2;
        }

        return new RenderBounds(left, top, left + drawWidth, top + drawHeight);
    }

    private float resolveVideoAspectRatio() {
        if (player == null) {
            return DEFAULT_VIDEO_ASPECT_RATIO;
        }
        int w = player.sourceWidth();
        int h = player.sourceHeight();
        if (w <= 0 || h <= 0) {
            w = player.width();
            h = player.height();
        }
        if (w > 0 && h > 0) {
            return (float) w / (float) h;
        }
        return DEFAULT_VIDEO_ASPECT_RATIO;
    }

    private record RenderBounds(int left, int top, int right, int bottom) {
    }
}
