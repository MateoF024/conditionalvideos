package org.mateof24.conditionalvideos.video;

// Where someone already watching a condition was when this client was told to join them. The time of arrival lets
// the player add whatever passed while the source was opening.
public record SyncPoint(int entry, double seconds, long receivedNanos) {
}
