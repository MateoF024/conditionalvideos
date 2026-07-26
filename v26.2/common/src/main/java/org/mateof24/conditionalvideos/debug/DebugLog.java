package org.mateof24.conditionalvideos.debug;

import org.lwjgl.opengl.GL11;
import org.mateof24.conditionalvideos.ConditionalVideos;

public final class DebugLog {
    public enum Area {
        LIFECYCLE, JOIN, CONDITION, QUEUE, SOURCE, BACKEND, QUALITY, PLAYLIST, NETWORK, CONFIG,
        PLAYBACK, RENDER, ENV
    }

    private static volatile boolean enabled;

    private DebugLog() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        boolean changed = enabled != value;
        enabled = value;
        applyWaterMediaLogLevel(value);
        if (changed) {
            ConditionalVideos.LOGGER.info("ConditionalVideos debug logging {}.", value ? "ENABLED" : "disabled");
            if (value) {
                dumpEnvironment();
            }
        }
    }

    public static void log(Area area, String format, Object... args) {
        if (!enabled) {
            return;
        }
        ConditionalVideos.LOGGER.info("[CV/" + area.name() + "] " + format, args);
    }

    // Must run only AFTER WaterMedia has loaded the FFmpeg natives (e.g. from the video backend),
    // never during mod init / config load: forcing the bytedeco avutil class to initialise early
    // poisons it permanently (NoClassDefFoundError) and breaks all video playback.
    public static void applyFfmpegLogLevel() {
        try {
            Class<?> avutil = Class.forName("org.bytedeco.ffmpeg.global.avutil");
            int level = avutil.getField(enabled ? "AV_LOG_VERBOSE" : "AV_LOG_ERROR").getInt(null);
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

    // One-shot environment/compat snapshot, logged when debug is enabled. Deliberately broad — driver,
    // GL, OS, Minecraft, WaterMedia and known-interfering mods — so a future regression (a driver/mod
    // conflict, or a WaterMedia/Minecraft change like the 1.21.1->1.21.11 render rewrite) leaves a
    // fingerprint in the log with no new code. Called on the render thread; the GL block is guarded.
    public static void dumpEnvironment() {
        log(Area.ENV, "=== ConditionalVideos diagnostics snapshot ===");
        log(Area.ENV, "OS: {} {} | Java {}", System.getProperty("os.name"), System.getProperty("os.arch"), System.getProperty("java.version"));
        log(Area.ENV, "Minecraft: {}", minecraftVersion());
        // 26.2 can run OpenGL or Vulkan; the backend decides whether video playback is possible at all.
        String backend = null;
        try {
            com.mojang.blaze3d.systems.DeviceInfo info = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo();
            backend = info.backendName();
            log(Area.ENV, "Renderer: backend='{}' device='{}' vendor='{}' driver='{}'",
                    backend, info.name(), info.vendorName(), info.driverInfo());
        } catch (Throwable t) {
            log(Area.ENV, "Renderer: <unavailable> ({})", t.toString());
        }
        // Raw GL is only legal while the GL backend is active: under Vulkan there is no context and the
        // call kills the process without throwing, so an unreadable backend skips the query as well.
        if (backend != null && !"Vulkan".equalsIgnoreCase(backend)) {
            try {
                log(Area.ENV, "GL: vendor='{}' renderer='{}' version='{}'",
                        GL11.glGetString(GL11.GL_VENDOR), GL11.glGetString(GL11.GL_RENDERER), GL11.glGetString(GL11.GL_VERSION));
            } catch (Throwable t) {
                log(Area.ENV, "GL: <unavailable> ({})", t.toString());
            }
        } else {
            log(Area.ENV, "GL: <skipped, backend={}>", backend == null ? "<unknown>" : backend);
        }
        try {
            log(Area.ENV, "WaterMedia: v{} ffmpegLoaded={} ffmpegError={} vulkanDecode={}",
                    org.watermedia.WaterMedia.VERSION,
                    org.watermedia.api.media.MediaAPI.ffmpegLoaded(),
                    org.watermedia.api.media.MediaAPI.ffmpegError(),
                    org.watermedia.api.media.MediaAPI.vulkanDecode());
            java.util.List<org.watermedia.WaterMedia.Failure> failures = org.watermedia.WaterMedia.failures();
            if (failures.isEmpty()) {
                log(Area.ENV, "WaterMedia boot: no non-fatal failures");
            } else {
                for (org.watermedia.WaterMedia.Failure failure : failures) {
                    log(Area.ENV, "WaterMedia boot FAILURE: api={} step={}", failure.api(), failure.step());
                }
            }
        } catch (Throwable t) {
            log(Area.ENV, "WaterMedia: <not loaded> ({})", t.toString());
        }
        log(Area.ENV, "Interfering mods present: sodium={} iris/oculus={}",
                anyClassPresent("net.caffeinemc.mods.sodium.client.SodiumClientMod",
                        "me.jellysquid.mods.sodium.client.SodiumClientMod"),
                anyClassPresent("net.irisshaders.iris.Iris", "net.coderbot.iris.Iris"));
        log(Area.ENV, "=== end snapshot ===");
    }

    private static String minecraftVersion() {
        try {
            Object version = Class.forName("net.minecraft.SharedConstants").getMethod("getCurrentVersion").invoke(null);
            try {
                return (String) version.getClass().getMethod("getName").invoke(version);
            } catch (Throwable ignored) {
            }
            try {
                return (String) version.getClass().getMethod("name").invoke(version);
            } catch (Throwable ignored) {
            }
            return String.valueOf(version);
        } catch (Throwable t) {
            return "<unknown>";
        }
    }

    private static boolean anyClassPresent(String... classNames) {
        for (String className : classNames) {
            try {
                Class.forName(className, false, DebugLog.class.getClassLoader());
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
