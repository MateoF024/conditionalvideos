package org.mateof24.conditionalvideos.video;

public final class VideoAudioState {
    private static boolean videoPlaying = false;

    private VideoAudioState() {
    }

    public static void setVideoPlaying(boolean playing) {
        videoPlaying = playing;
    }

    public static boolean isVideoPlaying() {
        return videoPlaying;
    }
}