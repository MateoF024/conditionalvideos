package org.mateof24.conditionalvideos.video.backend;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.VideoDiagnostics;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;

import java.net.URI;
import java.util.List;
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
    private boolean loggedFirstFrame;
    private MRL.Status lastLoggedStatus;
    private MediaQuality lastRequestedQuality;
    private boolean qualityCeilingReached;
    private int qualitySettleTicks;

    private static final AtomicInteger TEXTURE_SEQ = new AtomicInteger();
    private VideoFrameTexture frameTexture;
    private Identifier frameTextureId;

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

    public void init() {
        DebugLog.log(DebugLog.Area.BACKEND, "Initialising backend for '{}'.", source);
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
            // WaterMedia runs GL from its own thread, so tasks are marshalled to the render thread via
            // Minecraft#execute and wrapped in runIsolatedGl (snapshot/restore) so its raw GL stays
            // invisible to MC's unreliable GlStateManager cache (1.21.5+).
            Minecraft minecraft = Minecraft.getInstance();
            Executor renderExecutor = (Runnable task) -> minecraft.execute(() -> {
                if (DebugLog.enabled()) {
                    glUploadCount.incrementAndGet();
                }
                runIsolatedGl(task);
            });
            gfx = MediaAPI.glEngine(renderThread, renderExecutor);
            sfx = MediaAPI.alEngine();
            return true;
        } catch (Throwable throwable) {
            ConditionalVideos.LOGGER.warn("Failed to initialize WATERMeDIA v3 engines for '{}': {}", source, throwable.toString());
            errored = true;
            cleanup();
            return false;
        }
    }

    // Runs a WaterMedia GL task bracketed by a snapshot/restore of every binding it touches, so its raw
    // GL leaves MC's state (and GlStateManager's cache) exactly as it was. Only bindings restore; the
    // uploaded frame content survives.
    private static final int TEXTURE_UNITS_TO_PRESERVE = 8;

    private static void runIsolatedGl(Runnable task) {
        int activeUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] textureBindings = new int[TEXTURE_UNITS_TO_PRESERVE];
        int[] samplerBindings = new int[TEXTURE_UNITS_TO_PRESERVE];
        for (int i = 0; i < TEXTURE_UNITS_TO_PRESERVE; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            textureBindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            samplerBindings[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            // GREEN-SCREEN FIX: detach MC's bound sampler so WaterMedia's mip-less plane textures sample
            // with their own params. A bound mipmap sampler makes them sample as zero and the conversion
            // writes a solid green frame.
            GL33.glBindSampler(i, 0);
        }
        int unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int unpackRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int unpackSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int unpackSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int pixelUnpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        // Clean draw state for the conversion pass: MC leaves SCISSOR/depth/blend/etc. enabled mid-GUI,
        // which would clip or mask WaterMedia's quad. Saved and restored below.
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        java.nio.ByteBuffer colorMask = org.lwjgl.BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);

        boolean debug = DebugLog.enabled();
        if (debug) {
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                // drain pre-existing errors so we only report what WaterMedia's task produces
            }
        }
        try {
            task.run();
        } finally {
            if (debug) {
                int err = GL11.glGetError();
                if (err != GL11.GL_NO_ERROR) {
                    DebugLog.log(DebugLog.Area.BACKEND, "WaterMedia GL task raised glError 0x{} (scissor was {})",
                            Integer.toHexString(err), scissor);
                }
            }
            setEnabled(GL11.GL_SCISSOR_TEST, scissor);
            setEnabled(GL11.GL_DEPTH_TEST, depthTest);
            setEnabled(GL11.GL_BLEND, blend);
            setEnabled(GL11.GL_STENCIL_TEST, stencil);
            setEnabled(GL11.GL_CULL_FACE, cull);
            GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
            GL11.glDepthMask(depthMask);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pixelUnpackBuffer);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, unpackRowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, unpackSkipPixels);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, unpackSkipRows);
            for (int i = 0; i < TEXTURE_UNITS_TO_PRESERVE; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBindings[i]);
                GL33.glBindSampler(i, samplerBindings[i]);
            }
            GL13.glActiveTexture(activeUnit);
        }
    }

    private static void setEnabled(int cap, boolean enabled) {
        if (enabled) {
            GL11.glEnable(cap);
        } else {
            GL11.glDisable(cap);
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
            mrl = MediaAPI.mrl(source);
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
            ConditionalVideos.LOGGER.debug("MediaAPI.mrl() not ready yet for '{}': {}", source, throwable.toString());
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
        reinforceQuality();
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
            if (playerStatus != MediaPlayer.Status.PLAYING) {
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

    // index >= 0 selects that source explicitly; -1 falls back to WaterMedia's default source.
    private void startSourcePlayer(MRL.Source preferred, int index) {
        player = index >= 0
                ? MediaAPI.createPlayer(mrl, index, () -> gfx, () -> sfx)
                : MediaAPI.createPlayer(mrl, () -> gfx, () -> sfx);
        registerStatusListener();
        if (ffmpegLoaded()) {
            DebugLog.applyFfmpegLogLevel();
        }
        desiredQuality = resolveDesiredQuality(preferred);
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

    // Caches player status from the onStatus event (seeded once) so per-tick queries read one volatile
    // field instead of several native getters; the callback may run off-thread, hence volatile.
    private void registerStatusListener() {
        try {
            player.onStatus((previous, current) -> playerStatus = current);
        } catch (Throwable t) {
            ConditionalVideos.LOGGER.debug("onStatus() registration failed for '{}': {}", source, t.toString());
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

    // Surfaces a clear reason (plus WaterMedia boot failures) when FFmpeg failed to load, instead of a
    // generic timeout.
    private void reportFfmpegUnavailable() {
        ConditionalVideos.LOGGER.warn("Cannot play video '{}': WaterMedia's FFmpeg backend failed to load; "
                + "video playback is unavailable. Verify the WaterMedia and WaterMedia Binaries installation.", source);
        try {
            List<WaterMedia.Failure> failures = WaterMedia.failures();
            for (WaterMedia.Failure failure : failures) {
                ConditionalVideos.LOGGER.warn("  WaterMedia boot failure: api={}, step={}", failure.api(), failure.step());
            }
        } catch (Throwable ignored) {
        }
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
    private MediaQuality resolveDesiredQuality(MRL.Source preferred) {
        Set<MediaQuality> available;
        try {
            available = preferred.qualities().keySet();
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

    public void render(GuiGraphicsExtractor guiGraphics, int width, int height) {
        render(guiGraphics, width, height, 1f);
    }

    // 1.21.5+ uses a deferred GUI pipeline, so raw immediate-mode GL here would draw out of order. Instead
    // wrap WaterMedia's GL texture as a Blaze3D texture and blit it like any GUI sprite.
    public void render(GuiGraphicsExtractor guiGraphics, int width, int height, float alpha) {
        long texId = currentTextureId();
        if (texId <= 0) {
            return;
        }
        int texW = currentTextureWidth();
        int texH = currentTextureHeight();
        if (texW <= 0 || texH <= 0) {
            return;
        }
        Identifier id = ensureRegisteredTexture((int) texId, texW, texH);
        if (id == null) {
            return;
        }
        renderedAnyFrame = true;
        diagnostics.sample(source, player, gfx, texId, glUploadCount);

        RenderBounds bounds = calculateRenderBounds(width, height);
        float clampedAlpha = Math.max(0f, Math.min(1f, alpha));
        int color = (Math.round(clampedAlpha * 255f) << 24) | 0x00FFFFFF;
        guiGraphics.blit(
                RenderPipelines.GUI_TEXTURED,
                id,
                bounds.left(), bounds.top(),
                0f, 0f,
                bounds.right() - bounds.left(), bounds.bottom() - bounds.top(),
                texW, texH,
                texW, texH,
                color);
    }

    private long currentTextureId() {
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
        return texId;
    }

    private int currentTextureWidth() {
        if (player != null) {
            int w = player.width();
            if (w <= 0) {
                w = player.sourceWidth();
            }
            if (w > 0) {
                return w;
            }
        }
        if (gfx != null) {
            try {
                return gfx.width();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private int currentTextureHeight() {
        if (player != null) {
            int h = player.height();
            if (h <= 0) {
                h = player.sourceHeight();
            }
            if (h > 0) {
                return h;
            }
        }
        if (gfx != null) {
            try {
                return gfx.height();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    // Lazily registers a TextureManager entry wrapping WaterMedia's live frame texture; refreshes the
    // wrapped id/size on change. Returns the Identifier for GuiGraphicsExtractor.blit.
    private Identifier ensureRegisteredTexture(int glId, int texW, int texH) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return null;
            }
            if (frameTexture == null) {
                frameTexture = new VideoFrameTexture();
                frameTextureId = Identifier.fromNamespaceAndPath(
                        ConditionalVideos.MOD_ID, "video_frame/" + TEXTURE_SEQ.incrementAndGet());
                minecraft.getTextureManager().register(frameTextureId, frameTexture);
            }
            if (!frameTexture.update(glId, texW, texH)) {
                return null;
            }
            return frameTextureId;
        } catch (Throwable t) {
            ConditionalVideos.LOGGER.error("Failed to register video frame texture for '{}': {}", source, t.toString());
            return null;
        }
    }

    private void releaseFrameTexture() {
        if (frameTextureId != null) {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null) {
                    minecraft.getTextureManager().release(frameTextureId);
                }
            } catch (Throwable ignored) {
            }
            frameTextureId = null;
        }
        frameTexture = null;
    }

    // Wraps a WaterMedia-owned GL texture as a Blaze3D GlTexture. close() never deletes it: WaterMedia
    // owns and frees the GL texture.
    private static final class ForeignGlTexture extends GlTexture {
        // The wrapped frame is only ever sampled, never used as a render target, so it needs no shared
        // framebuffer cache; an own empty one keeps this decoupled from the GL device.
        private static final FrameBufferCache FRAME_BUFFER_CACHE = new FrameBufferCache();

        private boolean disposed;

        ForeignGlTexture(int glId, int width, int height) {
            super(GpuTexture.USAGE_TEXTURE_BINDING, "conditionalvideos-video", GpuFormat.RGBA8_UNORM,
                    width, height, 1, 1, glId, FRAME_BUFFER_CACHE);
        }

        @Override
        public void close() {
            disposed = true;
        }

        @Override
        public boolean isClosed() {
            return disposed;
        }
    }

    // AbstractTexture wrapping WaterMedia's live GL frame; rebuilt only when the id/size changes. Disposal
    // frees the view/sampler, never the foreign GL texture.
    private static final class VideoFrameTexture extends AbstractTexture {
        private int wrappedGlId = -1;
        private int wrappedWidth = -1;
        private int wrappedHeight = -1;

        boolean update(int glId, int width, int height) {
            if (texture != null && glId == wrappedGlId && width == wrappedWidth && height == wrappedHeight) {
                return true;
            }
            disposeGpu();
            try {
                ForeignGlTexture wrapped = new ForeignGlTexture(glId, width, height);
                this.texture = wrapped;
                this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
                this.textureView = RenderSystem.getDevice().createTextureView(wrapped);
                wrappedGlId = glId;
                wrappedWidth = width;
                wrappedHeight = height;
                return true;
            } catch (Throwable t) {
                ConditionalVideos.LOGGER.error("Failed to wrap video GL texture {}: {}", glId, t.toString());
                disposeGpu();
                return false;
            }
        }

        private void disposeGpu() {
            if (textureView != null) {
                try {
                    textureView.close();
                } catch (Throwable ignored) {
                }
                textureView = null;
            }
            if (texture != null) {
                try {
                    texture.close();
                } catch (Throwable ignored) {
                }
                texture = null;
            }
            sampler = null;
            wrappedGlId = -1;
            wrappedWidth = -1;
            wrappedHeight = -1;
        }

        @Override
        public void close() {
            disposeGpu();
        }
    }

    public void close() {
        cleanup();
    }

    private void cleanup() {
        closed = true;
        releaseFrameTexture();
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
