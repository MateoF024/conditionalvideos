package org.mateof24.conditionalvideos.debug;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.architectury.platform.Platform;
import org.lwjgl.opengl.GL11;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.CommonConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;

// Client side of the debug log. It is enabled from this installation's own common config (a server never
// switches it), drives WaterMedia's and FFmpeg's verbosity with it, and dumps the client environment once
// the renderer is up. Client-only: referenced solely from client code paths.
public final class ClientDebug {
    private static boolean environmentDumped;

    private ClientDebug() {
    }

    public static void init() {
        boolean enabled = CommonConfig.readDebugLogging(CommonConfig.configPath());
        DebugLog.setClientEnabled(enabled);
        applyWaterMediaLogLevel(enabled);
        if (enabled) {
            ConditionalVideos.LOGGER.info("ConditionalVideos client debug logging ENABLED.");
        }
    }

    // Applies a later read of the flag (the full common config load), reacting only to a real change.
    public static void applyFlag(boolean enabled) {
        if (!DebugLog.setClientEnabled(enabled)) {
            return;
        }
        applyWaterMediaLogLevel(enabled);
        ConditionalVideos.LOGGER.info("ConditionalVideos client debug logging {}.", enabled ? "ENABLED" : "disabled");
        if (enabled) {
            environmentDumped = false;
        }
    }

    public static void onClientTick() {
        if (!environmentDumped && DebugLog.clientEnabled()) {
            environmentDumped = true;
            dumpEnvironment();
        }
    }

    // Must run only AFTER WaterMedia has loaded the FFmpeg natives (e.g. from the video backend),
    // never during mod init / config load: forcing the bytedeco avutil class to initialise early
    // poisons it permanently (NoClassDefFoundError) and breaks all video playback.
    // Debug stops at WARNING: INFO/VERBOSE print every HLS segment URL straight to the native console.
    public static void applyFfmpegLogLevel() {
        try {
            Class<?> avutil = Class.forName("org.bytedeco.ffmpeg.global.avutil");
            int level = avutil.getField(DebugLog.clientEnabled() ? "AV_LOG_WARNING" : "AV_LOG_ERROR").getInt(null);
            avutil.getMethod("av_log_set_level", int.class).invoke(null, level);
        } catch (Throwable ignored) {
        }
    }

    private static void applyWaterMediaLogLevel(boolean debug) {
        try {
            Object logger = Class.forName("org.watermedia.WaterMedia").getField("LOGGER").get(null);
            String name = (String) logger.getClass().getMethod("getName").invoke(logger);
            Class<?> level = Class.forName("org.apache.logging.log4j.Level");
            Object target = level.getMethod("valueOf", String.class).invoke(null, debug ? "DEBUG" : "WARN");
            Class.forName("org.apache.logging.log4j.core.config.Configurator")
                    .getMethod("setLevel", String.class, level)
                    .invoke(null, name, target);
        } catch (Throwable ignored) {
        }
    }

    // One-shot client snapshot: platform, loader, renderer, WaterMedia and known-interfering mods, so a
    // driver/mod conflict or a WaterMedia/Minecraft change leaves a fingerprint with no new code. Runs on
    // the render thread from the first client tick, once the renderer exists.
    private static void dumpEnvironment() {
        DebugLog.client(DebugLog.Area.ENV, "=== ConditionalVideos client environment ===");
        DebugLog.client(DebugLog.Area.ENV, "{}", DebugLog.runtimeDescription());
        DebugLog.client(DebugLog.Area.ENV, "{}", DebugLog.platformDescription());
        // 26.2 can run OpenGL or Vulkan; the backend decides whether video playback is possible at all.
        String backend = null;
        try {
            DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
            backend = info.backendName();
            DebugLog.client(DebugLog.Area.ENV, "Renderer: backend='{}' device='{}' vendor='{}' driver='{}'",
                    backend, info.name(), info.vendorName(), info.driverInfo());
        } catch (Throwable t) {
            DebugLog.client(DebugLog.Area.ENV, "Renderer: <unavailable> ({})", t.toString());
        }
        // Raw GL is only legal while the GL backend is active: under Vulkan there is no context and the
        // call kills the process without throwing, so an unreadable backend skips the query as well.
        if (backend != null && !"Vulkan".equalsIgnoreCase(backend)) {
            try {
                DebugLog.client(DebugLog.Area.ENV, "GL: vendor='{}' renderer='{}' version='{}'",
                        GL11.glGetString(GL11.GL_VENDOR), GL11.glGetString(GL11.GL_RENDERER), GL11.glGetString(GL11.GL_VERSION));
            } catch (Throwable t) {
                DebugLog.client(DebugLog.Area.ENV, "GL: <unavailable> ({})", t.toString());
            }
        } else {
            DebugLog.client(DebugLog.Area.ENV, "GL: <skipped, backend={}>", backend == null ? "<unknown>" : backend);
        }
        try {
            DebugLog.client(DebugLog.Area.ENV, "WaterMedia: v{} loaded={} modules={}/{} ffmpegLoaded={} ffmpegError={} vulkanDecode={} | Binaries {}",
                    org.watermedia.WaterMedia.VERSION,
                    org.watermedia.WaterMedia.loaded(),
                    org.watermedia.WaterMedia.step(),
                    org.watermedia.WaterMedia.steps(),
                    org.watermedia.api.media.MediaAPI.ffmpegLoaded(),
                    org.watermedia.api.media.MediaAPI.ffmpegError(),
                    org.watermedia.api.media.MediaAPI.vulkanDecode(),
                    DebugLog.modVersion("watermedia_binaries"));
        } catch (Throwable t) {
            DebugLog.client(DebugLog.Area.ENV, "WaterMedia: <not loaded> ({})", t.toString());
        }
        DebugLog.client(DebugLog.Area.ENV, "Interfering mods present: sodium={} iris/oculus={}",
                anyClassPresent("net.caffeinemc.mods.sodium.client.SodiumClientMod",
                        "me.jellysquid.mods.sodium.client.SodiumClientMod"),
                anyClassPresent("net.irisshaders.iris.Iris", "net.coderbot.iris.Iris"));
        DebugLog.client(DebugLog.Area.ENV, "Config: conditions '{}', common '{}'",
                ConditionalVideosConfig.clientConfigPath(Platform.getGameFolder()), CommonConfig.configPath());
        DebugLog.client(DebugLog.Area.ENV, "=== end of client environment ===");
    }

    private static boolean anyClassPresent(String... classNames) {
        for (String className : classNames) {
            try {
                Class.forName(className, false, ClientDebug.class.getClassLoader());
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
