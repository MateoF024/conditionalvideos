package org.mateof24.conditionalvideos.api;

/**
 * Client-side callback for video playback lifecycle.
 *
 * <p>Listeners are invoked on the client render thread when a {@code VideoPlaybackScreen} opens
 * (start) and when it closes for any reason — natural end, skip-to-close, ESC, playback failure,
 * or a {@code stop} command/API call (end). Register via
 * {@link ConditionalVideosAPI#addPlaybackListener(PlaybackListener)}.</p>
 */
public interface PlaybackListener {
    /**
     * Called when playback of a condition begins on this client.
     *
     * @param conditionKey the condition identifier that started playing (e.g. {@code firstJoin},
     *                     {@code custom/my_event}); may be {@code null} for internally-keyed playback
     */
    default void onPlaybackStarted(String conditionKey) {
    }

    /**
     * Called when the active playback ends on this client.
     *
     * @param conditionKey the condition identifier that stopped playing; may be {@code null}
     */
    default void onPlaybackEnded(String conditionKey) {
    }
}
