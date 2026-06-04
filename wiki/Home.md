# ConditionalVideos — Developer Wiki

Welcome to the developer documentation for **ConditionalVideos**. This wiki is for **mod authors** who want to integrate with ConditionalVideos: register their own conditions and trigger, force, stop, or pause cinematic playback from their own code.

If you are a server owner or pack author looking for configuration docs, see the project **README** instead — this wiki only covers the programmatic API.

## Pages

- **[Home](Home.md)** — this page: concepts, where things run, and dependency setup.
- **[API Reference](API-Reference.md)** — every public type and method, parameters, return values, and the full list of condition keys.
- **[Custom Conditions & Examples](Custom-Conditions.md)** — end-to-end recipes for the most common integrations.

---

## What the API lets you do

The public surface lives in the package `org.mateof24.conditionalvideos.api` and is intentionally small and stable:

- **Register custom conditions** — define a `custom/<id>` condition that has no built-in detector. Your mod decides when it fires; server admins map videos to it in their config.
- **Trigger playback** — ask a player's client to play a configured condition, either honoring its "first time / repeatable" gate or forcing it unconditionally.
- **Control playback** — stop or toggle-pause the current video.
- **Observe playback** — query whether a video is playing/paused on the local client and which condition it is, and subscribe to start/end events.

Everything is exposed as **static methods** on `ConditionalVideosAPI`. You never instantiate it.

---

## The golden rule: where things run

> **Playback always happens on the client. The server can only *order* a client to play, stop, or pause.**

ConditionalVideos opens the video screen on the **client**. In multiplayer, the dedicated server detects server-side events and sends a control message to the target player's client over the mod's internal channel. This shapes the entire API:

| Your code runs on… | Use these methods | Why |
|---|---|---|
| **Server** (a `ServerPlayer` is available) | `triggerOnServer`, `triggerCustomOnServer`, `forcePlayOnServer`, `stopOnServer`, `togglePauseOnServer` | The order is routed to that player's client. |
| **Client** (client-only mod / singleplayer) | `playOnClient`, `stopOnClient`, `togglePauseOnClient`, and all state/event methods | Acts directly on the local client. |

**Environment safety:** every `*OnClient` method and the state queries are **no-ops in the wrong environment** (e.g. calling `playOnClient` on a dedicated server does nothing). You do **not** need to wrap calls in your own side checks, though doing so is still good practice.

In **singleplayer**, server-side methods work too (there is an integrated server), and the integrated server routes the order back to the local client — so prefer `*OnServer` when you have a `ServerPlayer`, even in singleplayer.

---

## Adding ConditionalVideos as a dependency

ConditionalVideos is built with [Architectury](https://docs.architectury.dev/) (Forge/Fabric on 1.20.1, NeoForge/Fabric on 1.21.1). The API package is plain Java + Minecraft types, so you can compile against it from your `common`/platform source sets.

Add the ConditionalVideos jar as a **compile-time** dependency. You only need it at compile time — at runtime users install the mod themselves, and you should treat it as a **soft/optional dependency** so your mod still loads without it.

```gradle
dependencies {
    // Compile against the API. Replace with the coordinate/file you use.
    compileOnly "org.mateof24:conditionalvideos:<version>"
    // or, if you keep the jar locally:
    // compileOnly files("libs/conditionalvideos-<version>.jar")
}
```

- **Maven group / artifact:** `org.mateof24` / `conditionalvideos`.
- **API package:** `org.mateof24.conditionalvideos.api`.

### Make it optional at runtime

Because ConditionalVideos may not be installed, guard your usage so your mod degrades gracefully:

```java
public final class CvBridge {
    public static final boolean PRESENT =
            net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("conditionalvideos");
    // On (Neo)Forge use ModList.get().isLoaded("conditionalvideos") instead.
}
```

```java
if (CvBridge.PRESENT) {
    ConditionalVideosAPI.triggerOnServer(player, "firstJoin");
}
```

> Touching `ConditionalVideosAPI` only inside an `if (PRESENT)` branch (or a separate class loaded lazily) prevents a `NoClassDefFoundError` when the mod is absent.

---

## A 60-second example

Register a custom condition during your mod's init, then fire it when your own event happens:

```java
// During mod init (any side):
ConditionalVideosAPI.registerCustomCondition(
        "my_event", "My Event", "Plays when the boss spawns");

// Later, on the server, when your event fires for a specific player:
ConditionalVideosAPI.triggerCustomOnServer(serverPlayer, "my_event");
```

A server admin adds videos to `custom/my_event` in `conditionalvideos-server.json` (the entry is seeded empty automatically), and the cinematic plays on that player's client.

Continue to the **[API Reference](API-Reference.md)** for the complete surface, or jump to **[Custom Conditions & Examples](Custom-Conditions.md)** for full recipes.
