package org.mateof24.conditionalvideos.video;

public final class VideoAudioState {
    private static boolean videoPlaying = false;
    private static boolean loadingActive = false;

    private VideoAudioState() {
    }

    public static void setVideoPlaying(boolean playing) {
        videoPlaying = playing;
    }

    public static boolean isVideoPlaying() {
        return videoPlaying;
    }

    public static void setLoadingActive(boolean active) {
        loadingActive = active;
    }

    public static boolean isLoadingActive() {
        return loadingActive;
    }

    public static boolean shouldMuteAudio() {
        return videoPlaying || loadingActive;
    }
}
