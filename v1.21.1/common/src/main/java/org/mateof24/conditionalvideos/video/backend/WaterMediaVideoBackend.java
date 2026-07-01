package org.mateof24.conditionalvideos.video.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.util.MediaQuality;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.Executor;

// Bridges one video source to a WaterMedia v3 player: acquires the MRL, starts/loops/seeks the
// player, caps the upload resolution to the window, and renders its texture full-screen.
public final class WaterMediaVideoBackend {
    private static final float DEFAULT_VIDEO_ASPECT_RATIO = 16.0F / 9.0F;
    private static final int MRL_ERROR_GRACE_TICKS = 20 * 30;
    private static final int MRL_RELOAD_SPACING_TICKS = 40;
    private static final int MAX_MRL_RELOAD_ATTEMPTS = 3;
    private static final long HOLD_AT_ZERO_THRESHOLD_MS = 100L;
    private static final int QUALITY_SETTLE_TICKS = 40;

    private final URI source;
    private final float configuredVolume;
    private float volumeMultiplier = 1f;
    private boolean startPaused;
    private boolean resumeOnReady;
    private boolean holdAtZero;

    private MRL mrl;
    private MediaPlayer player;
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
    private boolean loggedFirstFrame;
    private MRL.Status lastLoggedStatus;
    private int appliedMaxWidth = -1;
    private int appliedMaxHeight = -1;
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
            return player.texture() > 0 && (player.playing() || player.paused() || player.buffering());
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

    // True while WaterMedia is still resolving the MRL or buffering toward the first frame. The screen
    // uses this so a slow-but-progressing source (e.g. a long YouTube video) is never mistaken for a
    // stall: only genuine inactivity or a terminal error gives up. Long loads stop being penalised.
    public boolean isActivelyLoading() {
        if (closed || errored) {
            return false;
        }
        if (player != null) {
            try {
                return player.loading() || player.buffering() || player.waiting();
            } catch (Throwable ignored) {
                return false;
            }
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

    // Configurable patience (seconds -> ticks) for resolving an MRL, shared with the screen's first-frame
    // wait. Read live so config edits take effect on the next source without a restart.
    private int loadTimeoutTicks() {
        return ActiveConfigResolver.effectiveVideoLoadTimeoutSeconds() * 20;
    }

    public void setForcedQuality(MediaQuality quality) {
        this.forcedQuality = quality;
    }

    public void setStartPaused(boolean value) {
        this.startPaused = value;
        this.holdAtZero = value;
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
        boolean needsSeek = true;
        try {
            long ms = player.time();
            if (ms >= 0L && ms < 100L) {
                needsSeek = false;
            }
        } catch (Throwable ignored) {
        }
        if (needsSeek) {
            try {
                if (player.canSeek()) {
                    player.seek(0L);
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
                ConditionalVideos.LOGGER.debug("resume()/start() failed for '{}': {}", source, t2.toString());
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
            ConditionalVideos.LOGGER.debug("pause() failed for '{}': {}", source, t.toString());
        }
    }

    public void resume() {
        if (player == null || closed) {
            return;
        }
        try {
            player.resume();
        } catch (Throwable t) {
            ConditionalVideos.LOGGER.debug("resume() failed for '{}': {}", source, t.toString());
        }
    }

    public boolean seekToStart() {
        if (player == null || closed) {
            return false;
        }
        try {
            if (!player.canSeek()) {
                return false;
            }
            return player.seek(0L);
        } catch (Throwable t) {
            ConditionalVideos.LOGGER.debug("seek(0) failed for '{}': {}", source, t.toString());
            return false;
        }
    }

    public void init() {
        DebugLog.log(DebugLog.Area.BACKEND, "Initialising backend for '{}'.", source);
        applyMatureContentPolicy();
        if (!createEngines()) {
            return;
        }
        tryAcquireMrl();
    }

    // Builds a fresh GL/AL engine pair on the render thread. Called once at init and again whenever a
    // multi-source playlist advances to its next video, so each source gets a clean player+engine pair
    // (reusing a player across sources is what froze the texture; see the loop-freeze fix).
    private boolean createEngines() {
        try {
            Thread renderThread = Thread.currentThread();
            Executor renderExecutor = (Runnable r) -> RenderSystem.recordRenderCall(r::run);
            gfx = new GLEngine.Builder(renderThread, renderExecutor)
                    .setGenTexture(() -> GL11.glGenTextures())
                    .setBindTexture(GL11::glBindTexture)
                    .setTexParameter(GL11::glTexParameteri)
                    .setPixelStore(GL11::glPixelStorei)
                    .setDelTexture((int t) -> GL11.glDeleteTextures(t))
                    .build();
            sfx = ALEngine.buildDefault();
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
            ConditionalVideos.LOGGER.debug("Failed to apply mature-content policy to WaterMedia: {}", t.toString());
        }
    }

    private void tryAcquireMrl() {
        if (mrl != null || closed || errored) {
            return;
        }
        try {
            mrl = MediaAPI.getMRL(source.toString());
            if (mrl != null && mrl.status() == MRL.Status.EXPIRED) {
                try {
                    mrl.reload();
                } catch (Throwable t) {
                    ConditionalVideos.LOGGER.debug("MRL.reload() failed for '{}': {}", source, t.toString());
                }
            }
            if (mrl != null) {
                ConditionalVideos.LOGGER.info("Requested MRL load from WATERMeDIA v3: {}", source);
                DebugLog.log(DebugLog.Area.SOURCE, "MRL acquired for '{}' (initial status {}).", source, safeStatus());
            }
        } catch (Throwable throwable) {
            ConditionalVideos.LOGGER.debug("MediaAPI.getMRL() not ready yet for '{}': {}", source, throwable.toString());
        }
    }

    private MRL.Status safeStatus() {
        try {
            return mrl == null ? null : mrl.status();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void tick() {
        if (closed) {
            return;
        }
        if (!loggedFirstFrame && renderedAnyFrame) {
            loggedFirstFrame = true;
            DebugLog.log(DebugLog.Area.BACKEND, "First frame rendered for '{}'.", source);
        }
        if (player == null) {
            tickPreStart();
            return;
        }
        reinforcePlayerSettings();
        enforceHoldAtZero();
    }

    private void reinforcePlayerSettings() {
        if (!playerStarted || player == null || closed) {
            return;
        }
        reinforceQuality();
        applyMaxSizeCap();
    }

    // Caps uploaded frame dimensions so videos larger than the window never waste VRAM/upload
    // bandwidth. The cap preserves the native aspect ratio (a single scale to fit the window, never
    // upscaled), so the frame is never distorted; it only applies once the native size is known.
    private void applyMaxSizeCap() {
        if (player == null || closed) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            int winW = minecraft.getWindow().getWidth();
            int winH = minecraft.getWindow().getHeight();
            int srcW = player.sourceWidth();
            int srcH = player.sourceHeight();
            if (winW <= 0 || winH <= 0 || srcW <= 0 || srcH <= 0) {
                return;
            }
            double scale = Math.min((double) winW / srcW, (double) winH / srcH);
            int capW = scale >= 1.0 ? srcW : Math.max(1, (int) Math.round(srcW * scale));
            int capH = scale >= 1.0 ? srcH : Math.max(1, (int) Math.round(srcH * scale));
            if (capW == appliedMaxWidth && capH == appliedMaxHeight) {
                return;
            }
            boolean downscaling = capW < srcW || capH < srcH;
            boolean hadCap = appliedMaxWidth > 0 && (appliedMaxWidth < srcW || appliedMaxHeight < srcH);
            appliedMaxWidth = capW;
            appliedMaxHeight = capH;
            if (!downscaling && !hadCap) {
                return;
            }
            player.maxSize(capW, capH);
            DebugLog.log(DebugLog.Area.BACKEND, "Capped upload size to {}x{} (source {}x{}) for '{}'.", capW, capH, srcW, srcH, source);
        } catch (Throwable ignored) {
        }
    }

    // Re-asserts the desired quality on multi-variant streams that drift below it, but with
    // hysteresis: it requests the target once and, if the player cannot reach it within
    // QUALITY_SETTLE_TICKS, marks a ceiling and stops re-requesting. Without this a target the source
    // can never reach (e.g. HIGHEST on a 1080p stream) is re-requested every tick and keeps tearing
    // down the decoder. Does nothing when quality is unmanaged (desiredQuality null).
    private void reinforceQuality() {
        if (desiredQuality == null || desiredQuality == MediaQuality.UNKNOWN) {
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
                : current != MediaQuality.UNKNOWN && current.threshold >= desiredQuality.threshold;
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
                DebugLog.log(DebugLog.Area.QUALITY, "Quality ceiling for '{}': stuck at {} below target {} after {} ticks; stop re-requesting.", source, current, desiredQuality, QUALITY_SETTLE_TICKS);
            }
            return;
        }
        try {
            player.quality(desiredQuality);
            lastRequestedQuality = desiredQuality;
            qualitySettleTicks = 0;
            DebugLog.log(DebugLog.Area.QUALITY, "Requested quality {} for '{}' (was {}).", desiredQuality, source, current);
        } catch (Throwable ignored) {
        }
    }

    private void enforceHoldAtZero() {
        if (!holdAtZero || !playerStarted || player == null || closed) {
            return;
        }
        try {
            if (!player.playing()) {
                return;
            }
            try { player.pause(); } catch (Throwable ignored) { }
            try {
                if (player.time() > HOLD_AT_ZERO_THRESHOLD_MS && player.canSeek()) {
                    player.seek(0L);
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
            DebugLog.log(DebugLog.Area.SOURCE, "MRL '{}' status -> {}.", source, status);
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
                DebugLog.log(DebugLog.Area.SOURCE, "Retrying MRL '{}' (attempt {}/{}).", source, mrlReloadAttempts, MAX_MRL_RELOAD_ATTEMPTS);
                try {
                    mrl.reload();
                } catch (Throwable t) {
                    ConditionalVideos.LOGGER.debug("MRL.reload() failed for '{}': {}", source, t.toString());
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
                mrl.reload();
            } catch (Throwable t) {
                ConditionalVideos.LOGGER.debug("MRL.reload() failed for '{}': {}", source, t.toString());
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

    // Opens the player for the single resolved source. index >= 0 selects that source explicitly; -1
    // falls back to WaterMedia's default video/image source.
    private void startSourcePlayer(MRL.Source preferred, int index) {
        player = index >= 0
                ? MediaAPI.createPlayer(mrl, index, () -> gfx, () -> sfx)
                : MediaAPI.createPlayer(mrl, () -> gfx, () -> sfx);
        DebugLog.applyFfmpegLogLevel();
        desiredQuality = resolveDesiredQuality(preferred);
        appliedMaxWidth = -1;
        appliedMaxHeight = -1;
        appliedVolumeIntCache = Integer.MIN_VALUE;
        lastRequestedQuality = null;
        qualityCeilingReached = false;
        qualitySettleTicks = 0;
        applyQualityIfPossible();
        applyVolumeIfPossible();
        boolean willStartPaused = startPaused && !resumeOnReady;
        if (willStartPaused) {
            try {
                player.startPaused();
            } catch (Throwable t) {
                ConditionalVideos.LOGGER.debug("startPaused() failed for '{}', falling back to start()+pause(): {}", source, t.toString());
                try {
                    player.start();
                    player.pause();
                } catch (Throwable t2) {
                    ConditionalVideos.LOGGER.debug("start()+pause() fallback failed for '{}': {}", source, t2.toString());
                }
            }
            try {
                if (player.canSeek()) {
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

    // Index (into mrl.sources()) of the FIRST video source, or -1 to fall back to the default
    // video/image source. Only one source is ever played: whole-playlist MRLs (e.g. a YouTube playlist
    // URL that resolves to many videos) are intentionally not supported, as their sequential playback
    // stuttered, froze and skipped unreliably.
    private int resolveVideoSourceIndex() {
        try {
            int count = mrl.sourceCount();
            for (int i = 0; i < count; i++) {
                MRL.Source candidate = mrl.source(i);
                if (candidate != null && candidate.isVideo()) {
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
            MRL.Source video = mrl.videoSource();
            return video != null ? video : mrl.imageSource();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Single-variant sources (local files, direct mp4) expose no real quality ladder. Forcing a
    // quality on them makes reinforcePlayerSettings re-assert HIGHEST every tick, which re-opens the
    // decode pipeline and freezes the first frame (the "first local video per launch" bug). Returning
    // null leaves quality untouched for those; only genuinely multi-variant streams are managed.
    private MediaQuality resolveDesiredQuality(MRL.Source preferred) {
        Set<MediaQuality> available;
        try {
            available = preferred.availableQualities();
        } catch (Throwable ignored) {
            available = null;
        }
        if (available == null || available.size() <= 1) {
            DebugLog.log(DebugLog.Area.QUALITY, "Single-variant source for '{}'; leaving quality unmanaged.", source);
            return null;
        }
        MediaQuality target = forcedQuality != null ? forcedQuality : MediaQuality.HIGHEST;
        try {
            MediaQuality picked = MediaQuality.closest(available, target);
            if (picked != null && picked != MediaQuality.UNKNOWN) {
                DebugLog.log(DebugLog.Area.QUALITY, "Multi-variant source for '{}'; desired quality {} (target {}).", source, picked, target);
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
            ConditionalVideos.LOGGER.debug("player.quality({}) failed for '{}': {}", desiredQuality, source, t.toString());
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

        RenderBounds bounds = calculateRenderBounds(width, height);
        float clampedAlpha = Math.max(0f, Math.min(1f, alpha));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, clampedAlpha);
        RenderSystem._setShaderTexture(0, (int) texId);

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(bounds.left, bounds.bottom, 0).setUv(0f, 1f);
        builder.addVertex(bounds.right, bounds.bottom, 0).setUv(1f, 1f);
        builder.addVertex(bounds.right, bounds.top, 0).setUv(1f, 0f);
        builder.addVertex(bounds.left, bounds.top, 0).setUv(0f, 0f);
        BufferUploader.drawWithShader(builder.buildOrThrow());

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    public void close() {
        cleanup();
    }

    private void cleanup() {
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
                ConditionalVideos.LOGGER.debug("Failed to release player for '{}': {}", source, t.toString());
            }
            player = null;
        }
        if (gfx != null) {
            try {
                gfx.release();
            } catch (Throwable t) {
                ConditionalVideos.LOGGER.debug("Failed to release GFX engine for '{}': {}", source, t.toString());
            }
            gfx = null;
        }
        sfx = null;
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
            return player.error() || player.ended() || player.stopped();
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
