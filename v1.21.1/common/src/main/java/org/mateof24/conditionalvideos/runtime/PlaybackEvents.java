package org.mateof24.conditionalvideos.runtime;

import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.api.PlaybackListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PlaybackEvents {
    private static final List<PlaybackListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile String currentConditionKey;

    private PlaybackEvents() {
    }

    public static void addListener(PlaybackListener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(PlaybackListener listener) {
        LISTENERS.remove(listener);
    }

    public static String currentConditionKey() {
        return currentConditionKey;
    }

    public static void notifyStarted(String conditionKey) {
        currentConditionKey = conditionKey;
        for (PlaybackListener listener : LISTENERS) {
            try {
                listener.onPlaybackStarted(conditionKey);
            } catch (Throwable t) {
                ConditionalVideos.LOGGER.warn("PlaybackListener threw on start for '{}': {}", conditionKey, t.toString());
            }
        }
    }

    public static void notifyEnded(String conditionKey) {
        currentConditionKey = null;
        for (PlaybackListener listener : LISTENERS) {
            try {
                listener.onPlaybackEnded(conditionKey);
            } catch (Throwable t) {
                ConditionalVideos.LOGGER.warn("PlaybackListener threw on end for '{}': {}", conditionKey, t.toString());
            }
        }
    }
}
