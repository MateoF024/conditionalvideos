<h1 align="center">🎬 ConditionalVideos</h1>

<p align="center">
  <a href="https://fabricmc.net">
    <img alt="Fabric 1.20.1" src="https://img.shields.io/badge/Fabric-1.20.1-dbd0b4?style=for-the-badge" />
  </a>
  <a href="https://minecraftforge.net">
    <img alt="Forge 1.20.1" src="https://img.shields.io/badge/Forge-1.20.1-e04e14?style=for-the-badge" />
  </a>
  <a href="https://fabricmc.net">
    <img alt="Fabric 1.21.1" src="https://img.shields.io/badge/Fabric-1.21.1-dbd0b4?style=for-the-badge" />
  </a>
  <a href="https://neoforged.net">
    <img alt="NeoForge 1.21.1" src="https://img.shields.io/badge/NeoForge-1.21.1-f98010?style=for-the-badge" />
  </a>
  <a href="https://modrinth.com">
    <img alt="Environment" src="https://img.shields.io/badge/Env-Client%20%26%20Server-4a90d9?style=for-the-badge" />
  </a>
</p>

<p align="center">
  <a href="https://github.com/MateoF024/conditionalvideos/issues">
    <img alt="Report Issues" src="https://img.shields.io/badge/Report-Issues-red?style=for-the-badge&logo=github&logoColor=white" />
  </a>
</p>

<p align="center">
  Server-aware cinematic triggers for Minecraft. Configure conditions once and let clients play synced videos — local files, URLs, or full playlists — exactly when it matters.
</p>

---

## ✨ Overview

ConditionalVideos plays custom videos when specific in-game events are detected.
It is **not strictly client-side**: it runs on both client and dedicated server, with different responsibilities in each environment.

It is designed for curated gameplay experiences such as servers, story maps, quest packs, and roleplay setups where cinematic feedback improves immersion.

**Highlights**

- A dozen trigger types: first join, deaths (generic and per-killer), kills, advancements, dimension changes, item obtained, item crafted, recipe unlocked, totem used, sleeping in a bed, scoreboard thresholds, and mod-defined custom conditions.
- Per-condition **playlists** with crossfade/cut transitions, looping, overlays, per-clip volume, and timed cuts.
- A shared **common config** for client behavior (forced quality, cursor, sounds, mature-content blocking).
- Operator-driven **scoreboard** triggers and a server **command tree** to play/stop/pause videos on any player.
- A **playback queue** so overlapping triggers play in sequence instead of cutting each other off.
- A small, stable **public API** for other mods (see the [Developer Wiki](#-developer-api)).

---

## 🧩 Compatibility & Requirements

- **Minecraft 1.20.1:** Fabric, Forge — Java 17+
- **Minecraft 1.21.1:** Fabric, NeoForge — Java 21+
- **Client Config File:** `config/conditionalvideos.json`
- **Dedicated Server Config File:** `config/conditionalvideos-server.json`
- **Common Config File:** `config/conditionalvideos-common.json` (client behavior; also read by dedicated servers for the synced options)
- **Required Fabric API** (Fabric only)
- **Required** [**WATERMeDIA: Multimedia API v3**](https://modrinth.com/mod/watermedia) (client side) — **v3.0.0+**
- **Required** [**WATERMeDIA: Native Binaries**](https://modrinth.com/mod/watermedia-binaries) (client side, ships with WATERMeDIA)
- **Optional** [**WATERMeDIA: YouTube Extension**](https://modrinth.com/mod/watermedia-yt-plugin) — required only if your config uses YouTube URLs

> **WATERMeDIA v3** brings broader platform coverage out of the box (YouTube, Twitch/Kick clips, TikTok, Bluesky, Odysee, Google Drive/Dropbox/MediaFire, SoundCloud, IPTV, and more). Most of these "just work" as `source` URLs as long as they are reachable from the client.

---

## 🧭 Client vs Server (Important)

### Client Instance

- Creates and uses:
  - `config/conditionalvideos.json`
  - `config/conditionalvideos-common.json`
- In **singleplayer**:
  - Uses the local client config directly (the integrated server detects server-side conditions).
  - Local `source` paths are resolved from the local game directory (or absolute path).
  - URL sources (`http://`, `https://`, YouTube links) are streamed directly through WATERMeDIA.
- In **multiplayer**:
  - Acts as a **passive receiver** for server data.
  - Receives synced server config at runtime (it does **not** use your local client rules for server gameplay).
  - Downloads server-provided local-file videos to cache and reuses them on reconnect.
  - URL sources defined by the server are streamed directly by the client without going through the file transfer pipeline.
  - Cache location pattern:
    - `config/conditionalvideos/<server-id>/<condition-type>/<file>`
- On disconnect:
  - Synced runtime state is cleared and re-requested next time you join.

### Dedicated Server Instance

- Creates and uses:
  - `config/conditionalvideos-server.json`
- Owns the **authoritative config** for connected clients.
- **Detects** server-side conditions (item obtained/crafted, recipe unlocked, totem used, bed sleep, scoreboard thresholds) and **orders** the relevant client to play through an internal control channel.
- On player join:
  - Sends synced config to clients.
  - Publishes a manifest of configured **local-file** videos (URLs are excluded — the client fetches them by itself).
  - Serves missing/outdated files to clients in 32 KiB chunks. Each chunk is verified by `sha256` before it is moved into the client cache.
- If you replace a video file on server or change path/config:
  - Clients detect hash mismatch and re-download updated files automatically.

### What this means in practice

- Clients still need the mod installed to receive/play synced videos from the server.
- You can install video assets only on the server and clients will receive needed files automatically.
- URLs (including YouTube links) do **not** need to be deployed to the server filesystem — the client streams them directly. They only need to be reachable from the client.
- Local client config stays useful for singleplayer testing, but does not override dedicated-server behavior in multiplayer.
- For multiplayer content pipelines, treat `conditionalvideos-server.json` as the source of truth.

---

## ⏳ Multiplayer Loading Screen

Whenever any condition is about to play a **local-file** video that lives in the server manifest but has not been downloaded into the local cache yet, a full-screen loading overlay (black background + animated spinner + translatable text) takes over while the file is transferred in the background. Vanilla Minecraft sounds are muted during the wait, and as soon as the download finishes the loading screen hands off seamlessly to the playback screen.

- Applies to **every** condition — not just the first-join cinematic.
- The loading text is **singular/plural aware**: `Loading video…` for a single clip, `Loading videos…` for a playlist.
- For `firstJoin` specifically, the server also puts the player in Spectator from the moment the loading screen appears until the playback finishes, so the world is invisible and silent during the wait. Other conditions only show the overlay; gameplay keeps running underneath them, matching the existing behavior of the playback screen.
- URL/YouTube sources skip the loading screen — WATERMeDIA handles buffering directly inside the playback screen.
- Already-cached files skip the loading screen and jump straight to playback.
- If the server is unreachable or the download stalls, the loading screen times out after about a minute and the player resumes normal gameplay.

---

## 🎯 Supported Conditions

Supports multiple gameplay trigger types so you can tailor cinematic playback to meaningful moments during progression. Each condition is configured with a **playlist** (see [Condition Object Schema](#-condition-object-schema)).

### Simple conditions (single config key)

- **First connection** (`firstJoin`)
  Plays an intro/cinematic when the player enters the world/session. **Always prioritized**: even if another condition fires during world load, `firstJoin` plays first and the rest are queued behind it.

- **General death** (`playerDeath`)
  Default fallback cinematic for any player death event.

- **Totem used** (`totemUsed`)
  Plays when a Totem of Undying saves the player.

- **Bed sleep** (`bedSleep`)
  Plays when the player begins sleeping in a bed.

### Keyed conditions (map of `id → condition`)

- **Death by entity** (`deathByEntity`)
  Overrides the generic death with custom videos based on the killer entity ID (`namespace:entity`), such as `minecraft:zombie` or `minecraft:creeper`. When it matches, it takes precedence over `playerDeath`.

- **Entity kill** (`entityKilled`)
  Plays when the player defeats specific configured entity IDs.

- **Advancement completion** (`advancementCompleted`)
  Plays when configured advancements are completed.

- **Dimension transition** (`dimensionChanged`)
  Plays when the player enters configured dimensions.

- **Item obtained** (`itemObtained`)
  Plays when the player obtains a configured item ID (pickup, craft, or smelt result). *Obtaining* is not the same as *holding* — it does not fire from already-owned items, and `/give` is not guaranteed to emit an event (best-effort).

- **Item crafted** (`itemCrafted`)
  Plays when a configured item is **actually crafted** (inventory 2×2 grid or crafting table) — the act of crafting itself, never a pickup, `/give`, or smelt. It is independent from `itemObtained`, so a single craft may fire both if both are configured for that item.

- **Recipe unlocked** (`recipeUnlocked`)
  Plays when a configured recipe is newly unlocked in the player's recipe book.

- **Scoreboard threshold** (`scoreboard`)
  Plays when a player's score on a configured objective satisfies a comparison (see below).

- **Custom** (`custom`)
  Has no built-in detector — fired only by the [public API](#-developer-api) or the `play` command. Useful for other mods to attach cinematics to their own events.

### Scoreboard comparators

Each `scoreboard` entry compares a player's score on an objective against a `value` using a `comparator`:

| `comparator` | Fires when |
|---|---|
| `equal` | `score == value` |
| `less` | `score < value` |
| `greater` | `score > value` |
| `lessOrEqual` | `score <= value` |
| `greaterOrEqual` | `score >= value` (default) |

- **Edge-triggered, re-armable:** a satisfied comparison fires **once** and will not repeat every time the score changes. It only re-arms (becomes eligible to fire again) after the comparison turns **false** and crosses back to true.
- **Persistent across reconnects:** because scores persist with the world, a comparison that was already satisfied before you logged out will **not** re-fire when you rejoin. It only fires again if the score drops below the comparator and crosses it again while online.

---

## ⚙️ Common Configuration (`conditionalvideos-common.json`)

A separate file controls client playback behavior. It is created automatically with safe defaults.

| Property | Type | Default | Authority | Description |
|---|---|---|---|---|
| `videoQuality` | `string` | `AUTO` | Server-authoritative | `AUTO` picks the best available stream. Any other value (e.g. `LOWEST`, `LOW`, `MEDIUM`, `HIGH`, `HIGHEST`) **strictly forces** that quality. |
| `alwaysShowCursor` | `boolean` | `false` | Server-authoritative | When `true`, the playback screen never auto-hides the mouse cursor. |
| `allowGameSounds` | `boolean` | `false` | Server-authoritative | When `true`, vanilla Minecraft sounds keep playing during a video (by default they are muted). |
| `blockMatureContent` | `boolean` | `true` | **Client-only** | When `true`, mature-content sources are blocked. **A server cannot override this** — it is always read from the client's own file. |

> **Authority:** in multiplayer, the three server-authoritative options come from the server's common config when available, falling back to the client's local file otherwise. `blockMatureContent` is **never** synced and is **always** enforced from the local client file, in both singleplayer and multiplayer.

> **Forced quality is precise, not a crop:** forcing a quality only has an effect on **multi-variant** sources (YouTube and similar platforms). A direct file or single-variant `.mp4` has only one stream, so forcing is a no-op there. Available qualities also depend on what the YouTube extension can extract.

---

## 🧱 Condition Object Schema

Each condition entry holds a **playlist** under `videos: [...]` plus a few shared flags. Each item in `videos` is a `VideoEntry` describing a single clip plus its overlay/skip/loop/transition options.

### Shared condition fields

| Property | Type | Required | Description |
|---|---|---|---|
| `repeatableInSameSession` | `boolean` | Yes | If `false`, this condition key can trigger once per session. If `true`, it can trigger repeatedly during the same session. |
| `playlistLoop` | `boolean` | No | When `true`, after the last entry finishes the playlist restarts from the first. Default `false`. |
| `videos` | `VideoEntry[]` | Yes | One or more entries played in order. A playlist of length 1 behaves like a single-video setup. |

### Scoreboard-only fields

`scoreboard` entries add two fields **at the condition level** (alongside the shared fields above):

| Property | Type | Required | Description |
|---|---|---|---|
| `value` | `int` | Yes | The threshold compared against the player's score. |
| `comparator` | `string` | No | One of `equal`, `less`, `greater`, `lessOrEqual`, `greaterOrEqual`. Default `greaterOrEqual`. |

### `VideoEntry` fields

| Property | Type | Required | Description |
|---|---|---|---|
| `source` | `string` | Yes | Either a path to a local video file (relative to the Minecraft game directory or absolute) **or** a URL (`http://`, `https://`, YouTube link). |
| `skippable` | `boolean` | No | If `true`, the user can skip playback. Default `true`. Forced to `true` when `videoLoop = true`. |
| `videoLoop` | `boolean` | No | If `true`, the entry loops seamlessly until the player skips it. Default `false`. Requires `skippable = true`. |
| `enableBackground` | `boolean` | No | Enables a solid-color full-screen background behind the video. Default `true`. |
| `colorBackground` | `string` | No | Hex color in `#RRGGBB` or `#AARRGGBB` format. Default `#000000`. |
| `videoTitle` | `string` | No | Optional title overlay. Supports legacy Minecraft format codes (`&6`, `&l`, `&r`, etc.). |
| `videoTitlePosition` | `string` | No | `topLeft`, `topRight`, `bottomLeft`, `bottomRight`. Default `bottomLeft`. |
| `videoDescription` | `string` | No | Optional description overlay. Supports legacy format codes. |
| `videoDescriptionPosition` | `string` | No | `topLeft`, `topRight`, `bottomLeft`, `bottomRight`. Default `bottomLeft`. |
| `titleTextScale` | `float` | No | Multiplier applied to the title font. Default `1.0`. |
| `descriptionTextScale` | `float` | No | Multiplier applied to the description font. Default `1.0`. |
| `textBoxOpacity` | `float` | No | `0.0`–`1.0` opacity of the text box behind the title/description. Omit to keep the legacy auto-tied-to-text alpha. |
| `videoVolume` | `float` | No | `0.0`–`1.0` volume for this entry. Default `1.0`. |
| `transition` | `string` | No | Transition applied **when entering this entry** from the previous one in the playlist. Accepts `cut` (default) or `fadeOut/In`. Ignored for the first entry or when the playlist has length 1. |
| `nextAt` | `float` | No | Time in seconds (counted from the start of this entry) at which to cut/transition to the next entry. Omit to wait until the video ends naturally. |

> **Loop semantics:** `videoLoop = true` implies `skippable = true`. The skip hint stays hidden after the first loop iteration so the on-screen text does not flicker on every replay.

> **Playlist transitions:** `transition` is a property of the *incoming* entry. For example, if `videos[1].transition = "fadeOut/In"`, the player fades out `videos[0]` while fading in `videos[1]`. `cut` is an instant frame-perfect swap.

---

## 🌐 Source Resolution

Each `source` is classified automatically:

| Pattern | Treated as | Resolved by |
|---|---|---|
| `http://...`, `https://...` | URL | Streamed by WATERMeDIA directly. Never enters the server↔client file transfer pipeline. |
| `youtube.com/watch?v=...`, `youtu.be/...`, etc. | URL | Same as above; requires the **WATERMeDIA YouTube Extension** mod on the client. |
| Anything else | Local file | Resolved against the client cache (multiplayer) or against the game directory / absolute path (singleplayer). |

In multiplayer the server only ships **local-file** sources to clients. URLs in the synced server config are passed through verbatim, and each client opens them independently.

---

## 📁 Path Rules by Environment

### Singleplayer / Client-only local playback
- `source` can be:
  - Relative path (resolved from game directory)
  - Absolute path
  - URL / YouTube link

### Dedicated server playback for connected clients
- Server resolves local `source` paths from the server filesystem.
- Client does **not** need that same path to exist locally — server-provided files are streamed and cached automatically.
- URLs configured on the server are sent verbatim and resolved on each client.

> Recommendation: keep all server video assets in a dedicated folder (for example `videos/`) and reference them with relative paths in `conditionalvideos-server.json`.

---

## 🧑‍⚖️ Server Commands

A command tree lets operators drive playback on any player. Available to **permission level 2+** (so it works from command blocks and OPs), and from the server console.

```
/conditionalvideos play <targets> <condition>
/conditionalvideos stop <targets>
/conditionalvideos pause <targets>
```

- **`play`** forces the given condition to play on each target, ignoring the once-per-session gate. The `<condition>` argument auto-completes with the keys that currently have at least one video in the active config.
- **`stop`** closes the active playback on each target and clears their pending queue.
- **`pause`** toggles pause/resume on each target's current video (a pause icon appears in the top-right corner).

**Condition keys** use a bare key for simple conditions or a `type/key` form for keyed ones:

| Key form | Examples |
|---|---|
| Bare | `firstJoin`, `playerDeath`, `totemUsed`, `bedSleep` |
| `entityKilled/<entity>` | `entityKilled/minecraft:warden` |
| `deathByEntity/<entity>` | `deathByEntity/minecraft:creeper` |
| `advancement/<id>` | `advancement/minecraft:story/mine_diamond` |
| `dimension/<id>` | `dimension/minecraft:the_nether` |
| `itemObtained/<item>` | `itemObtained/minecraft:diamond` |
| `itemCrafted/<item>` | `itemCrafted/minecraft:crafting_table` |
| `recipeUnlocked/<recipe>` | `recipeUnlocked/minecraft:furnace` |
| `scoreboard/<objective>` | `scoreboard/kills` |
| `custom/<id>` | `custom/my_event` |

> Note: the command/API key for advancement and dimension conditions uses the `advancement/...` and `dimension/...` prefixes, while the **config JSON** stores them under the `advancementCompleted` and `dimensionChanged` maps.

---

## ⌨️ In-Game Controls

By default the **skip key is unbound**, which falls back to `ESC`:

- **Tap `ESC`** → skip to the next playlist entry (or close the playback if it is the last one). Looped entries are interrupted as well.
- **Hold `ESC` for ~1 second, then release** → close the playback screen entirely without queuing the next entry. The release-after-hold behavior prevents the in-world pause menu from toggling repeatedly if you keep the key pressed.

If you **bind a dedicated skip key** (Options → Controls → *Conditional Videos*), that key becomes the only active skip key (tap = skip / hold = close), and any other key — including `ESC` — does nothing while a video is playing. The on-screen skip hint always shows the name of the **currently active** key.

Other behavior:

- The mouse cursor auto-hides after a few seconds of inactivity and reappears the moment the mouse moves (unless `alwaysShowCursor` is enabled).
- Vanilla Minecraft sounds are muted while a video (or the multiplayer loading screen) is on screen (unless `allowGameSounds` is enabled).
- Minecraft toast notifications (advancements, recipes, system) are hidden while a video is playing so they do not obstruct it, and reappear once playback ends.

`skippable = false` disables both the tap and hold behaviors for that entry; the player must wait for it to finish or for the playlist to advance via `nextAt` / `videoLoop = false`.

---

## 🪢 Playback Queue

Only one video plays at a time. If a new condition triggers while a video (or the loading screen) is already on screen, it is **postponed**, not dropped:

- Triggers are queued **FIFO** and play in order once the current video closes, chaining until the queue is empty.
- The same condition is not queued twice, and the queue is capped to avoid unbounded growth.
- `firstJoin` is always guaranteed to play first; anything that fires during world load waits behind it.
- A `stop` command (or the equivalent API call) both stops the current video and clears the pending queue.
- Disconnecting clears the queue.

---

## 🔞 Mature Content Blocking

`blockMatureContent` (default `true`, **client-only and not overridable by the server**) protects the client from adult-content sources:

- URLs configured by a **server** that point to a mature-content site are blocked — the client does not even attempt to play them.
- **Singleplayer / local** videos in your own config are subject to the same rule.
- Protection combines a best-effort domain blocklist (checked before a stream is opened) with WATERMeDIA's own platform classification. Blocked entries are skipped silently (logged, no crash) and the playlist advances to the next entry.

> Domain coverage is best-effort; raw CDNs that WATERMeDIA does not classify as a platform may not be detected.

---

## 🌍 Localization

UI and command strings ship in four locales out of the box:

- `en_us`
- `es_es`
- `es_ar`
- `es_mx`

Additional translations can be added by dropping a new `<locale>.json` into a resource pack under `assets/conditionalvideos/lang/`.

---

## 🔁 Session Tracking Field

The config includes one internal tracking field managed by the mod:

- **`consumedConditionSessions`**
    - Stores condition/session keys that were already consumed when `repeatableInSameSession` is `false`.
    - Prevents non-repeatable conditions from replaying in the same session.

> You usually should not edit this field manually unless you intentionally want to reset session trigger history for testing.

---

## 🔄 Config Migration

The on-disk schema is versioned via `configVersion`. The current version is **3**.

- Configs created by ConditionalVideos 1.1.x (legacy `video: "..."` per condition) are folded into single-entry `videos: [...]` playlists, and a backup is written next to the file as `conditionalvideos.json.bak-v<previousVersion>`.
- The 1.2.0 → 1.3.0 (`v2` → `v3`) migration is **additive**: new condition maps are simply created empty; existing entries and `consumedConditionSessions` are preserved.

> **Config safety:** a single malformed entry (typo) **never** wipes the file. Entries are parsed leniently — a bad one is skipped and logged (once), while every valid entry is kept. On a JSON syntax error the file is left **untouched** and defaults are used only in memory for that session, so you can fix the typo without losing your work.

---

## 🗂️ Example `config/conditionalvideos.json` / `config/conditionalvideos-server.json`

Only `firstJoin` ships preconfigured by default; every other condition is written as an empty `{}` until you add videos to it. The example below is filled in to show the shape of each condition type (`configVersion: 3`).

```json
{
  "configVersion": 3,
  "firstJoin": {
    "repeatableInSameSession": false,
    "playlistLoop": false,
    "videos": [
      {
        "source": "videos/intro_part1.mp4",
        "skippable": true,
        "enableBackground": true,
        "colorBackground": "#FF000000",
        "videoTitle": "&6&lWelcome",
        "videoTitlePosition": "topLeft",
        "videoDescription": "&fEnjoy your journey",
        "videoDescriptionPosition": "topLeft",
        "titleTextScale": 1.4,
        "textBoxOpacity": 0.5,
        "videoVolume": 0.8,
        "nextAt": 8.0
      },
      {
        "source": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "skippable": true,
        "videoVolume": 1.0,
        "transition": "fadeOut/In"
      }
    ]
  },
  "playerDeath": {
    "repeatableInSameSession": true,
    "videos": [
      { "source": "videos/death/default_death.mp4", "videoTitle": "&cYou Died" }
    ]
  },
  "totemUsed": {
    "repeatableInSameSession": true,
    "videos": [
      { "source": "videos/totem.mp4", "videoTitle": "&eSaved by a Totem" }
    ]
  },
  "bedSleep": {},
  "entityKilled": {
    "minecraft:warden": {
      "repeatableInSameSession": false,
      "videos": [
        { "source": "videos/kills/warden.mp4", "skippable": false, "videoTitle": "&5&lEpic Victory" }
      ]
    }
  },
  "deathByEntity": {
    "minecraft:creeper": {
      "repeatableInSameSession": false,
      "videos": [
        { "source": "videos/death/creeper_death.mp4", "videoTitle": "&aBoom..." }
      ]
    }
  },
  "advancementCompleted": {
    "minecraft:story/mine_diamond": {
      "repeatableInSameSession": false,
      "videos": [
        { "source": "videos/advancements/diamond.mp4", "videoTitle": "&bDiamonds!" }
      ]
    }
  },
  "dimensionChanged": {
    "minecraft:the_nether": {
      "repeatableInSameSession": true,
      "playlistLoop": true,
      "videos": [
        { "source": "videos/dimensions/nether_loop.mp4", "videoLoop": true, "videoTitle": "&cEntered the Nether" }
      ]
    }
  },
  "itemObtained": {
    "minecraft:diamond": {
      "repeatableInSameSession": false,
      "videos": [
        { "source": "videos/items/first_diamond.mp4", "videoTitle": "&bFirst Diamond" }
      ]
    }
  },
  "itemCrafted": {
    "minecraft:crafting_table": {
      "repeatableInSameSession": false,
      "videos": [
        { "source": "videos/items/first_table.mp4", "videoTitle": "&6Crafting Table" }
      ]
    }
  },
  "recipeUnlocked": {
    "minecraft:furnace": {
      "repeatableInSameSession": false,
      "videos": [
        { "source": "videos/recipes/furnace.mp4", "videoTitle": "&7Furnace Unlocked" }
      ]
    }
  },
  "scoreboard": {
    "kills": {
      "repeatableInSameSession": false,
      "value": 100,
      "comparator": "greaterOrEqual",
      "videos": [
        { "source": "videos/scoreboard/100_kills.mp4", "videoTitle": "&c100 Kills!" }
      ]
    }
  },
  "custom": {},
  "consumedConditionSessions": []
}
```

---

## 🧰 Developer API

Other mods can register **custom conditions** and **trigger / control playback** through a small, stable public API in the `org.mateof24.conditionalvideos.api` package.

📖 **Full technical documentation lives in the [Developer Wiki](wiki/Home.md):**

- [Home — getting started, where things run, dependency setup](wiki/Home.md)
- [API Reference — every public method, types, and condition keys](wiki/API-Reference.md)
- [Custom Conditions & Examples — end-to-end recipes](wiki/Custom-Conditions.md)

---

## 🛠️ Support

- Found a bug or want to request a feature?

  [<img alt="Report Issues" src="https://img.shields.io/badge/Report-Issues-red?style=for-the-badge&logo=github&logoColor=white" />](https://github.com/MateoF024/conditionalvideos)
