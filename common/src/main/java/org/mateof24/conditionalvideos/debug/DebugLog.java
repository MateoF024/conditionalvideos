package org.mateof24.conditionalvideos.debug;

import org.mateof24.conditionalvideos.ConditionalVideos;

public final class DebugLog {
    public enum Area {
        LIFECYCLE, JOIN, CONDITION, QUEUE, SOURCE, BACKEND, QUALITY, PLAYLIST, NETWORK, CONFIG
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
}
