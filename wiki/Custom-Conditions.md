# Custom Conditions & Examples

End-to-end recipes for integrating with ConditionalVideos. Read **[Home](Home.md)** for the client/server model and **[API Reference](API-Reference.md)** for the full surface first.

All snippets assume you have guarded API access behind a "mod present" check (see [Home → Make it optional at runtime](Home.md#make-it-optional-at-runtime)). They are shown unguarded here for clarity.

---

## 1. Register and fire a custom condition (server-side)

The most common integration: define a `custom/<id>` cinematic and play it when your own server-side event happens.

**Step 1 — register during init (any side):**

```java
import org.mateof24.conditionalvideos.api.ConditionalVideosAPI;

public final class MyMod {
    public void onInitialize() {
        ConditionalVideosAPI.registerCustomCondition(
                "boss_defeated",            // id  -> config key "custom/boss_defeated"
                "Boss Defeated",            // display name (informational)
                "Plays when the raid boss dies"); // description (informational)
    }
}
```

When a world starts, ConditionalVideos seeds `custom/boss_defeated` into the active config as an empty entry (only if missing). A server admin fills it in:

```json
"custom": {
  "boss_defeated": {
    "repeatableInSameSession": true,
    "videos": [
      { "source": "videos/boss_outro.mp4", "videoTitle": "&6Victory!" }
    ]
  }
}
```

**Step 2 — fire it for a player when your event happens (server):**

```java
ServerPlayer player = /* the player who landed the killing blow */;

// Honors the "first time / repeatable" gate configured by the admin:
boolean sent = ConditionalVideosAPI.triggerCustomOnServer(player, "boss_defeated");

if (!sent) {
    // Either the admin didn't configure a video for it, or it was a
    // non-repeatable condition already consumed this session.
}
```

That's it — the cinematic plays on `player`'s client (or queues behind whatever is already playing).

---

## 2. Trigger a built-in condition from your mod

You can also trigger any built-in condition by key — useful to reuse a configured cinematic from your own logic.

```java
// Respect the session gate (plays at most once per session if not repeatable):
ConditionalVideosAPI.triggerOnServer(player, "advancement/minecraft:end/kill_dragon");

// Force it regardless of the gate (like the /conditionalvideos play command):
ConditionalVideosAPI.forcePlayOnServer(player, "dimension/minecraft:the_end");
```

Remember the prefix difference: advancement/dimension keys use `advancement/...` and `dimension/...`, even though their config maps are `advancementCompleted` / `dimensionChanged`. See [API Reference → Condition keys](API-Reference.md#condition-keys).

---

## 3. Client-only mod / singleplayer convenience

If your mod is client-side (or you just want to play something locally in singleplayer), use the client methods. They are no-ops on a dedicated server, so they are safe to call from shared code.

```java
// Play a configured condition on the local client (forced, no gate):
ConditionalVideosAPI.playOnClient("custom/intro");

// Control the current playback:
ConditionalVideosAPI.togglePauseOnClient();
ConditionalVideosAPI.stopOnClient();
```

> Prefer the server methods when you have a `ServerPlayer` — even in singleplayer the integrated server will route the order back to the local client, keeping behavior identical to multiplayer.

---

## 4. React to playback start/end

Use a `PlaybackListener` to pause your own systems during a cinematic and restore them afterward.

```java
import org.mateof24.conditionalvideos.api.ConditionalVideosAPI;
import org.mateof24.conditionalvideos.api.PlaybackListener;

ConditionalVideosAPI.addPlaybackListener(new PlaybackListener() {
    @Override
    public void onPlaybackStarted(String conditionKey) {
        MyHud.setHidden(true);
    }

    @Override
    public void onPlaybackEnded(String conditionKey) {
        MyHud.setHidden(false);
    }
});
```

- Called on the **client render thread** — keep handlers fast and thread-safe.
  - `onPlaybackEnded` fires for every termination path (natural end, skip, close, failure, `stop`).
  - `conditionKey` may be `null`.

---

## 5. Query playback state

```java
if (ConditionalVideosAPI.isPlaying()) {
    // a video is on screen on this client
}

if (ConditionalVideosAPI.isPaused()) {
    // ...and it is currently paused
}

ConditionalVideosAPI.currentConditionKey().ifPresent(key -> {
    // e.g. "firstJoin", "custom/boss_defeated"
});
```

All of these are safe on any side (they return `false`/empty off-client).

---

## 6. Stop or pause a player remotely (server)

```java
// Stop the current video and clear that player's pending queue:
ConditionalVideosAPI.stopOnServer(player);

// Toggle pause/resume on that player's current video:
ConditionalVideosAPI.togglePauseOnServer(player);
```

---

## Patterns & gotchas

- **Soft dependency.** Touch `ConditionalVideosAPI` only when the mod is loaded (see [Home](Home.md#make-it-optional-at-runtime)). Keep API calls in a separate class so the classloader doesn't fail if the mod is absent.
  - **Register early, every launch.** The custom-condition registry is in-memory and not persisted. Register during init so seeding (at world start) can pick it up.
  - **Seeding never clobbers.** Seeding only adds **missing** `custom/<id>` entries as empty objects, never edits existing ones, and skips a config that isn't valid JSON. Admins remain in full control of the file.
  - **A key must have videos to play.** `triggerOnServer` / `forcePlayOnServer` return `false` if the resolved condition has no videos — a clean way to detect "no cinematic configured".
  - **The queue is respected.** Triggers never cut off a video already on screen; they are postponed and played in order. Use `stopOnServer`/`stopOnClient` if you genuinely need to interrupt (it also clears the queue).
  - **`firstJoin` wins the start.** During world load, `firstJoin` is guaranteed to play before any other condition; anything you trigger early is queued behind it.

---

See also: **[Home](Home.md)** · **[API Reference](API-Reference.md)**
