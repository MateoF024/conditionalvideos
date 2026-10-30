package org.mateof24.conditionalvideos.video;

import org.mateof24.conditionalvideos.debug.DebugLog;

public final class VideoAudioState {
    private static volatile boolean videoPlaying = false;
    private static volatile boolean loadingActive = false;
    private static volatile boolean allowGameSounds = false;

    private VideoAudioState() {
    }

    public static void setAllowGameSounds(boolean allow) {
        boolean wasMuted = shouldMuteAudio();
        allowGameSounds = allow;
        logChange(wasMuted);
    }

    public static boolean allowGameSounds() {
        return allowGameSounds;
    }

    public static void setVideoPlaying(boolean playing) {
        boolean wasMuted = shouldMuteAudio();
        videoPlaying = playing;
        logChange(wasMuted);
    }

    public static boolean isVideoPlaying() {
        return videoPlaying;
    }

    public static void setLoadingActive(boolean active) {
        boolean wasMuted = shouldMuteAudio();
        loadingActive = active;
        logChange(wasMuted);
    }

    public static boolean isLoadingActive() {
        return loadingActive;
    }

    public static boolean shouldMuteAudio() {
        return (videoPlaying || loadingActive) && !allowGameSounds;
    }

    private static void logChange(boolean wasMuted) {
        boolean muted = shouldMuteAudio();
        if (muted != wasMuted) {
            DebugLog.client(DebugLog.Area.AUDIO, "Sound categories except master {} (video={}, loading={}, allowGameSounds={}).",
                    muted ? "silenced" : "back to their sliders", videoPlaying, loadingActive, allowGameSounds);
        }
    }
}
