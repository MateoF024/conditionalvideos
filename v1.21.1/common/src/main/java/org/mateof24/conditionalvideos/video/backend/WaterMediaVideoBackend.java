package org.mateof24.conditionalvideos.video.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.media.players.MediaPlayer;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.Executor;

public final class WaterMediaVideoBackend {
    private static final float DEFAULT_VIDEO_ASPECT_RATIO = 16.0F / 9.0F;
    private static final int MRL_LOAD_TIMEOUT_TICKS = 20 * 60;
    private static final int MRL_ERROR_GRACE_TICKS = 20 * 30;
    private static final long HOLD_AT_ZERO_THRESHOLD_MS = 100L;

    private final URI source;
    private final float configuredVolume;
    private float volumeMultiplier = 1f;
    private boolean startPaused;
    private boolean resumeOnReady;
    private boolean repeatRequested;
    private boolean holdAtZero;

    private MRL mrl;
    private MediaPlayer player;
    private GFXEngine gfx;
    private SFXEngine sfx;
    private MRL.Quality desiredQuality = MRL.Quality.HIGHEST;

    private boolean renderedAnyFrame;
    private boolean closed;
    private boolean errored;
    private boolean playerStarted;
    private int mrlWaitTicks;
    private int mrlErrorTicks;
    private int appliedVolumeIntCache = Integer.MIN_VALUE;

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
            if (player.texture() <= 0) {
                return false;
            }
            MediaPlayer.Status status = player.status();
            return status == MediaPlayer.Status.PLAYING
                    || status == MediaPlayer.Status.PAUSED
                    || status == MediaPlayer.Status.BUFFERING;
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

    public void setStartPaused(boolean value) {
        this.startPaused = value;
        this.holdAtZero = value;
    }

    public void setRepeat(boolean value) {
        this.repeatRequested = value;
        applyRepeatIfPossible();
    }

    private void applyRepeatIfPossible() {
        if (player == null || closed) {
            return;
        }
        try {
            player.repeat(repeatRequested);
        } catch (Throwable t) {
            ConditionalVideos.LOGGER.debug("player.repeat({}) failed for '{}': {}", repeatRequested, source, t.toString());
        }
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
        } catch (Throwable throwable) {
            ConditionalVideos.LOGGER.warn("Failed to initialize WATERMeDIA v3 engines for '{}': {}", source, throwable.toString());
            errored = true;
            cleanup();
            return;
        }

        tryAcquireMrl();
    }

    private void tryAcquireMrl() {
        if (mrl != null || closed || errored) {
            return;
        }
        try {
            mrl = MediaAPI.getMRL(source.toString());
            if (mrl != null && mrl.expired()) {
                try {
                    mrl.reload();
                } catch (Throwable t) {
                    ConditionalVideos.LOGGER.debug("MRL.reload() failed for '{}': {}", source, t.toString());
                }
            }
            if (mrl != null) {
                ConditionalVideos.LOGGER.info("Requested MRL load from WATERMeDIA v3: {}", source);
            }
        } catch (Throwable throwable) {
            ConditionalVideos.LOGGER.debug("MediaAPI.getMRL() not ready yet for '{}': {}", source, throwable.toString());
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
        reinforcePlayerSettings();
        enforceHoldAtZero();
    }

    private void reinforcePlayerSettings() {
        if (!playerStarted || player == null || closed) {
            return;
        }
        try {
            MRL.Quality current = player.quality();
            if (current != null && current != MRL.Quality.UNKNOWN
                    && desiredQuality != null && desiredQuality != MRL.Quality.UNKNOWN
                    && current.threshold < desiredQuality.threshold) {
                player.quality(desiredQuality);
            }
        } catch (Throwable ignored) {
        }
        if (repeatRequested) {
            try {
                if (!player.repeat()) {
                    player.repeat(true);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void enforceHoldAtZero() {
        if (!holdAtZero || !playerStarted || player == null || closed) {
            return;
        }
        try {
            MediaPlayer.Status status = player.status();
            if (status != MediaPlayer.Status.PLAYING) {
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
            if (mrlWaitTicks >= MRL_LOAD_TIMEOUT_TICKS) {
                ConditionalVideos.LOGGER.warn("MediaAPI never produced an MRL for '{}' after {}s.", source, MRL_LOAD_TIMEOUT_TICKS / 20);
                errored = true;
                cleanup();
                return;
            }
            tryAcquireMrl();
            return;
        }
        boolean busy;
        try {
            busy = mrl.busy();
        } catch (Throwable ignored) {
            busy = false;
        }
        if (mrl.error()) {
            if (busy) {
                mrlErrorTicks = 0;
                mrlWaitTicks++;
                if (mrlWaitTicks >= MRL_LOAD_TIMEOUT_TICKS) {
                    ConditionalVideos.LOGGER.warn("Timed out waiting for busy MRL '{}' to settle.", source);
                    errored = true;
                    cleanup();
                }
                return;
            }
            mrlErrorTicks++;
            if (mrlErrorTicks < MRL_ERROR_GRACE_TICKS) {
                if (mrlErrorTicks % 20 == 0) {
                    try {
                        MRL.invalidate(source);
                    } catch (Throwable ignored) {
                    }
                    mrl = null;
                    tryAcquireMrl();
                }
                return;
            }
            ConditionalVideos.LOGGER.warn("MRL persistently failed to load for '{}' after {}s.", source, MRL_ERROR_GRACE_TICKS / 20);
            errored = true;
            cleanup();
            return;
        }
        mrlErrorTicks = 0;
        if (!mrl.ready() || busy) {
            mrlWaitTicks++;
            if (mrlWaitTicks >= MRL_LOAD_TIMEOUT_TICKS) {
                ConditionalVideos.LOGGER.warn("Timed out waiting for MRL '{}' to resolve.", source);
                errored = true;
                cleanup();
            }
            return;
        }
        try {
            MRL.Source preferred = mrl.videoSource();
            if (preferred == null) {
                preferred = mrl.imageSource();
            }
            if (preferred == null) {
                ConditionalVideos.LOGGER.warn("MRL '{}' resolved but reported no playable source.", source);
                errored = true;
                cleanup();
                return;
            }
            player = mrl.createPlayer(gfx, sfx);
            desiredQuality = pickBestQuality(preferred);
            applyQualityIfPossible();
            applyVolumeIfPossible();
            applyRepeatIfPossible();
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
            applyRepeatIfPossible();
            ConditionalVideos.LOGGER.info("Started WATERMeDIA v3 player for '{}' (quality={}, startPaused={}, repeat={}, holdAtZero={}).",
                    source, player.quality(), willStartPaused, repeatRequested, holdAtZero);
        } catch (Throwable throwable) {
            ConditionalVideos.LOGGER.warn("Failed to start WATERMeDIA v3 player for '{}': {}", source, throwable.toString());
            mrlErrorTicks++;
            if (mrlErrorTicks >= MRL_ERROR_GRACE_TICKS) {
                errored = true;
                cleanup();
            } else {
                player = null;
            }
        }
    }

    private MRL.Quality pickBestQuality(MRL.Source preferred) {
        try {
            Set<MRL.Quality> available = preferred.availableQualities();
            if (available != null && !available.isEmpty()) {
                MRL.Quality picked = MRL.Quality.closest(available, MRL.Quality.HIGHEST);
                if (picked != null && picked != MRL.Quality.UNKNOWN) {
                    return picked;
                }
            }
        } catch (Throwable ignored) {
        }
        return MRL.Quality.HIGHEST;
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
        if (mrl != null) {
            if (errored) {
                try {
                    MRL.invalidate(source);
                } catch (Throwable ignored) {
                }
            }
            mrl = null;
        }
    }

    public boolean hasFinished() {
        if (closed) {
            return true;
        }
        if (player == null) {
            return false;
        }
        try {
            MediaPlayer.Status status = player.status();
            return status == MediaPlayer.Status.ERROR
                    || status == MediaPlayer.Status.ENDED
                    || status == MediaPlayer.Status.STOPPED;
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
        int w = player.width();
        int h = player.height();
        if (w > 0 && h > 0) {
            return (float) w / (float) h;
        }
        return DEFAULT_VIDEO_ASPECT_RATIO;
    }

    private record RenderBounds(int left, int top, int right, int bottom) {
    }
}
