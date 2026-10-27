package org.mateof24.conditionalvideos.debug;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.watermedia.api.media.GFXEngine;
import org.watermedia.api.media.MediaPlayer;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

// Per-source video diagnostics sampled ~1/s while debug logging is on (one instance per backend), written
// only when a sample says something: the first one, a player clock drifting from wall time, a frozen
// texture (every second while it lasts), the recovery, a format change, and a heartbeat every 30 s.
// Correlates the player clock/status/fps, the GL upload rate, and the centre texel of the frame texture
// (read back through a raw-GL FBO, so the identical code works on every branch and loader). When the
// frame is frozen it points at the likely layer:
//   clock ADVANCING + texture unchanged  -> a stalled present/upload
//   clock STALLED while audio continues   -> an A/V-clock stall
//   ZERO GL uploads/s                     -> WaterMedia stopped submitting frames
//   uploads > 0 but texture unchanged     -> the GL upload itself
public final class VideoDiagnostics {
    private static final long SAMPLE_INTERVAL_NANOS = 1_000_000_000L;
    private static final long CLOCK_TOLERANCE_MS = 300L;
    private static final int HEARTBEAT_SAMPLES = 30;
    private static final int TEXEL_CHANNELS = 4;

    private long lastSampleNanos;
    private long lastTimeMs = Long.MIN_VALUE;
    private String lastStatus = "";
    private String lastCentre = "";
    private String lastFormat = "";
    private int frozenSeconds;
    private int quietSamples;
    private boolean anomalous;

    // Call once per rendered frame with the GL texture currently shown; throttles itself to ~1/s.
    // uploadCounter is incremented by the render executor for every WaterMedia GL task, then read and
    // reset here to report uploads-per-second (0 = WaterMedia submitted no GL work this interval).
    public void sample(URI source, MediaPlayer player, GFXEngine gfx, long textureId, AtomicInteger uploadCounter) {
        if (!DebugLog.clientEnabled() || player == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastSampleNanos < SAMPLE_INTERVAL_NANOS) {
            return;
        }
        boolean first = lastSampleNanos == 0L;
        long wallMs = (now - lastSampleNanos) / 1_000_000L;
        lastSampleNanos = now;

        int uploads = uploadCounter == null ? -1 : uploadCounter.getAndSet(0);
        try {
            String status = String.valueOf(player.status());
            long timeMs = player.time();
            long durMs = player.duration();
            float fps = player.fps();
            int w = player.width();
            int h = player.height();
            int srcW = player.sourceWidth();
            int srcH = player.sourceHeight();

            int texW = w > 0 ? w : srcW;
            int texH = h > 0 ? h : srcH;
            if ((texW <= 0 || texH <= 0) && gfx != null) {
                texW = gfx.width();
                texH = gfx.height();
            }
            String centre = readCentreTexel(textureId, texW, texH);

            long timeDelta = (lastTimeMs == Long.MIN_VALUE || timeMs < 0) ? Long.MIN_VALUE : timeMs - lastTimeMs;
            boolean centreValid = !centre.isEmpty() && !centre.startsWith("fbo");
            boolean centreFrozen = centreValid && centre.equals(lastCentre);
            boolean playing = "PLAYING".equals(status);
            frozenSeconds = (playing && centreFrozen) ? frozenSeconds + 1 : 0;
            long drift = timeDelta == Long.MIN_VALUE ? 0L : timeDelta - wallMs;
            boolean clockOff = playing && "PLAYING".equals(lastStatus) && Math.abs(drift) > CLOCK_TOLERANCE_MS;
            String format = String.format(Locale.ROOT, "%.1f fps %dx%d %dms", fps, texW, texH, durMs);

            String reason;
            if (first) {
                reason = "first sample";
            } else if (frozenSeconds > 0) {
                reason = null;
            } else if (clockOff) {
                reason = "clock " + (drift > 0 ? "jumped " : "lagged ") + Math.abs(drift) + "ms against wall time";
            } else if (anomalous) {
                reason = "recovered";
            } else if (!format.equals(lastFormat)) {
                reason = "format changed";
            } else if (++quietSamples >= HEARTBEAT_SAMPLES) {
                reason = "heartbeat";
            } else {
                reason = "";
            }
            anomalous = frozenSeconds > 0 || clockOff;
            lastStatus = status;
            lastFormat = format;
            if (timeMs >= 0) {
                lastTimeMs = timeMs;
            }
            if (centreValid) {
                lastCentre = centre;
            }
            if (reason != null && reason.isEmpty()) {
                return;
            }
            quietSamples = 0;

            String deltaStr = timeDelta == Long.MIN_VALUE ? "?" : (timeDelta >= 0 ? "+" : "") + timeDelta + "ms";
            StringBuilder line = new StringBuilder(192);
            line.append("status=").append(status)
                    .append(" time=").append(timeMs).append("ms(").append(deltaStr).append(')')
                    .append(" fps=").append(String.format(Locale.ROOT, "%.1f", fps))
                    .append(" dur=").append(durMs).append("ms")
                    .append(" uploads/s=").append(uploads)
                    .append(" tex=").append(textureId).append(' ').append(texW).append('x').append(texH)
                    .append(" centre=").append(centre.isEmpty() ? "n/a" : centre);
            if (frozenSeconds > 0) {
                line.append(" [FROZEN ~").append(frozenSeconds).append('s');
                if (timeDelta != Long.MIN_VALUE && timeDelta > 0L) {
                    line.append("; clock ADVANCING -> present/upload");
                } else if (timeDelta == 0L) {
                    line.append("; clock STALLED (audio may continue) -> A/V sync");
                }
                if (uploads == 0) {
                    line.append("; ZERO GL uploads -> WaterMedia not submitting");
                } else if (uploads > 0) {
                    line.append("; uploads ran but texture unchanged -> GL upload");
                }
                line.append(']');
            } else {
                line.append(" [").append(reason).append(']');
            }
            DebugLog.client(DebugLog.Area.PLAYBACK, "{} for '{}'", line, source);
        } catch (Throwable t) {
            DebugLog.client(DebugLog.Area.PLAYBACK, "diagnostics sample failed for '{}': {}", source, t.toString());
        }
    }

    // Reads the centre texel of a GL texture through a throwaway read-FBO, saving and restoring the FBO
    // bindings and read-buffer it touches. Raw GL only, so it behaves identically on every loader/branch.
    private static String readCentreTexel(long textureId, int texW, int texH) {
        if (textureId <= 0 || texW <= 0 || texH <= 0) {
            return "";
        }
        int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int fbo = GL30.glGenFramebuffers();
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            return readTexelRGBA((int) textureId, texW / 2, texH / 2);
        } catch (Throwable t) {
            return "";
        } finally {
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GL11.glReadBuffer(prevReadBuffer);
            GL30.glDeleteFramebuffers(fbo);
        }
    }

    // Attaches texId to the bound read-FBO (COLOR_ATTACHMENT0) and reads one texel as RGBA. Returns the
    // tuple, or "fbo0x<status>" when the attachment is not framebuffer-complete (e.g. a plane texture).
    private static String readTexelRGBA(int texId, int x, int y) {
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texId, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            return "fbo0x" + Integer.toHexString(status);
        }
        ByteBuffer buf = BufferUtils.createByteBuffer(TEXEL_CHANNELS);
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return "(" + (buf.get(0) & 0xFF) + "," + (buf.get(1) & 0xFF) + "," + (buf.get(2) & 0xFF) + "," + (buf.get(3) & 0xFF) + ")";
    }
}
