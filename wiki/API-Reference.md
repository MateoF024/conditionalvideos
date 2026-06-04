# API Reference

Complete reference for the public API in `org.mateof24.conditionalvideos.api`.

- [`ConditionalVideosAPI`](#conditionalvideosapi) — the static facade (your entry point)
- [`CustomCondition`](#customcondition) — describes a mod-defined condition
- [`PlaybackListener`](#playbacklistener) — client-side playback callbacks
- [Condition keys](#condition-keys) — the string format every method uses

All methods are **static**. The class is `final` and cannot be instantiated. See **[Home](Home.md)** for the client-vs-server model that governs which methods to call.

---

## `ConditionalVideosAPI`

Fully-qualified: `org.mateof24.conditionalvideos.api.ConditionalVideosAPI`.

### Custom condition registration

#### `void registerCustomCondition(CustomCondition condition)`
Registers a custom condition so it is dispatchable by id and discoverable in config. `null` is ignored. Registering the same id again replaces the previous entry.

Registered conditions are **seeded into the active config file as empty entries** when a world starts — but only when the file does not already contain them. Existing entries are never altered, and a config file that cannot be parsed as JSON is left untouched. Admins add videos under `custom/<id>` to make it playable.

#### `void registerCustomCondition(String id)`
Convenience overload — equivalent to `registerCustomCondition(new CustomCondition(id))`.

#### `void registerCustomCondition(String id, String displayName, String description)`
Convenience overload — equivalent to `registerCustomCondition(new CustomCondition(id, displayName, description))`.

#### `boolean isCustomConditionRegistered(String id)`
Returns `true` if a custom condition with this id has been registered. Trims the id; `null` returns `false`.

#### `Collection<CustomCondition> registeredCustomConditions()`
Returns an immutable snapshot of all registered custom conditions.

> **When to register:** during your mod's common/init phase. Registration is process-global (an in-memory registry); it is **not** persisted, so re-register on every launch. Seeding into the config happens at `SERVER_STARTING`, so register before a world is loaded.

### Server-side triggering

Call these when you have a `ServerPlayer`. The order is routed to that player's client over the S2C control channel. All are no-ops for `null`/blank arguments.

#### `boolean triggerOnServer(ServerPlayer player, String conditionKey)`
Triggers a condition for a player, **honoring its "first time / repeatable" gating** (the same gate the built-in detectors use).

- `conditionKey` — a [condition key](#condition-keys), e.g. `firstJoin`, `itemObtained/minecraft:diamond`, `custom/my_event`.
- **Returns** `true` if an order was sent — i.e. the condition resolved, has at least one video, and passed the session gate. Returns `false` otherwise (unknown key, no videos, or already consumed this session for a non-repeatable condition).

#### `boolean triggerCustomOnServer(ServerPlayer player, String customId)`
Convenience for `triggerOnServer(player, "custom/" + customId)`. Same gating and return semantics.

#### `boolean forcePlayOnServer(ServerPlayer player, String conditionKey)`
Plays a condition for a player **ignoring session gating** (mirrors the `/conditionalvideos play` command). Validates that the condition resolves and has at least one video.

- **Returns** `true` if an order was sent (resolved + has videos), `false` otherwise.
- Unlike `triggerOnServer`, this does not consume the once-per-session state and will replay even if already shown.

#### `void stopOnServer(ServerPlayer player)`
Orders the player's client to stop the current playback and clear its pending queue.

#### `void togglePauseOnServer(ServerPlayer player)`
Orders the player's client to toggle pause/resume on the current video.

> **Gating vs. forcing.** `triggerOnServer` respects `repeatableInSameSession` and the per-session "already consumed" record, exactly like the built-in conditions. `forcePlayOnServer` bypasses that gate. Both still respect the **playback queue**: if a video is already on screen, the new one is postponed (it does not cut the current video off).

### Client-side triggering

Call these from a client-only mod or for singleplayer convenience. Each is a **no-op** unless running on the client with an initialized `Minecraft` instance.

#### `void playOnClient(String conditionKey)`
Plays the given condition on the local client, resolving it against the active config. This is a **forced** play (no session gate), matching the command path.

#### `void stopOnClient()`
Stops the current playback on the local client and clears its pending queue.

#### `void togglePauseOnClient()`
Toggles pause/resume on the local client's current video.

### Client-side state queries

Each returns a safe default when not on the client.

#### `boolean isPlaying()`
`true` if a video is currently on screen on this client.

#### `boolean isPaused()`
`true` if a video is playing **and** currently paused.

#### `Optional<String> currentConditionKey()`
The [condition key](#condition-keys) currently playing on this client, if any. Empty when nothing is playing (or for internally-keyed playback with no key).

### Client-side playback events

#### `void addPlaybackListener(PlaybackListener listener)`
Registers a [`PlaybackListener`](#playbacklistener). Listeners are invoked on the client render thread.

#### `void removePlaybackListener(PlaybackListener listener)`
Removes a previously registered listener.

---

## `CustomCondition`

A `record` describing a mod-defined condition. Fully-qualified: `org.mateof24.conditionalvideos.api.CustomCondition`.

```java
public record CustomCondition(String id, String displayName, String description)
```

| Component | Meaning |
|---|---|
| `id` | Unique, non-blank identifier. Becomes the config key `custom/<id>`. Trimmed; **throws** `IllegalArgumentException` if blank and `NullPointerException` if `null`. |
| `displayName` | Human-readable name; falls back to `id` when blank. Informational (tooling). |
| `description` | Optional one-line description; never `null` (normalized to empty string when unset). |

**Constructors**

- `new CustomCondition(String id)` — `displayName` and `description` default to `id` / empty.
- `new CustomCondition(String id, String displayName, String description)` — full form.

**Methods**

- `String conditionKey()` — returns `custom/<id>`, the wire/config key for this condition.

> A custom condition has **no built-in detector**. Your mod decides when it fires (via the trigger methods), and server admins map videos to `custom/<id>` in their config. The display metadata is purely informational.

---

## `PlaybackListener`

A client-side callback interface for the playback lifecycle. Fully-qualified: `org.mateof24.conditionalvideos.api.PlaybackListener`. Both methods are `default` (no-ops), so implement only what you need.

```java
public interface PlaybackListener {
    default void onPlaybackStarted(String conditionKey) {}
    default void onPlaybackEnded(String conditionKey) {}
}
```

- **`onPlaybackStarted(conditionKey)`** — called when a `VideoPlaybackScreen` opens.
- **`onPlaybackEnded(conditionKey)`** — called when playback ends for **any** reason: natural end, skip-to-close, the skip/close key, playback failure, or a `stop` command/API call.

Notes:

- Invoked on the **client render thread** — keep handlers short and thread-safe; offload heavy work.
- `conditionKey` may be `null` for internally-keyed playback.
- Register via `ConditionalVideosAPI.addPlaybackListener(...)`.

```java
ConditionalVideosAPI.addPlaybackListener(new PlaybackListener() {
    @Override public void onPlaybackEnded(String key) {
        if ("custom:cutscene".equals(key)) {
            // re-enable your HUD, resume music, etc.
        }
    }
});
```

---

## Condition keys

Every trigger/resolve method takes a **condition key** string. Simple conditions use a bare key; keyed conditions use a `type/key` form (split on the **first** `/`, so the key part may itself contain `/` and `:`).

| Key form | Examples | Config location |
|---|---|---|
| `firstJoin` | `firstJoin` | top-level object |
| `playerDeath` | `playerDeath` | top-level object |
| `totemUsed` | `totemUsed` | top-level object |
| `bedSleep` | `bedSleep` | top-level object |
| `entityKilled/<entity>` | `entityKilled/minecraft:warden` | `entityKilled` map |
| `deathByEntity/<entity>` | `deathByEntity/minecraft:creeper` | `deathByEntity` map |
| `advancement/<id>` | `advancement/minecraft:story/mine_diamond` | `advancementCompleted` map |
| `dimension/<id>` | `dimension/minecraft:the_nether` | `dimensionChanged` map |
| `itemObtained/<item>` | `itemObtained/minecraft:diamond` | `itemObtained` map |
| `itemCrafted/<item>` | `itemCrafted/minecraft:crafting_table` | `itemCrafted` map |
| `recipeUnlocked/<recipe>` | `recipeUnlocked/minecraft:furnace` | `recipeUnlocked` map |
| `scoreboard/<objective>` | `scoreboard/kills` | `scoreboard` map |
| `custom/<id>` | `custom/my_event` | `custom` map |

> ⚠️ **Prefix vs. config map name.** The advancement and dimension keys use the prefixes **`advancement/`** and **`dimension/`** in API/command keys, but their config maps are named **`advancementCompleted`** and **`dimensionChanged`**. All other types share the same name in both places.

A key only "resolves" (and therefore plays) if the config has that entry **with at least one video**. `triggerOnServer`/`forcePlayOnServer` return `false` for keys that don't resolve, so you can use the boolean result to detect "no cinematic configured for this".

---

## Threading & environment summary

| Concern | Behavior |
|---|---|
| Server methods (`*OnServer`) | Call with a valid `ServerPlayer`; safe in singleplayer (integrated server) and dedicated servers. |
| Client methods (`*OnClient`), state queries | No-op off-client; safe to call from common code. |
| Listeners | Invoked on the client render thread. |
| Custom condition registry | In-memory, process-global, not persisted; re-register each launch. |
| Config seeding | Happens at world start (`SERVER_STARTING`); never overwrites existing entries or a malformed file. |

See **[Custom Conditions & Examples](Custom-Conditions.md)** for complete, copy-pasteable integrations.
