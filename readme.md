<h1 align="center">🎬 ConditionalVideos</h1>

<p align="center">
  <a href="https://fabricmc.net">
    <img alt="Fabric version" src="https://img.shields.io/badge/Fabric-1.20.1-dbd0b4?style=for-the-badge" />
  </a>
  <a href="https://minecraftforge.net">
    <img alt="Forge version" src="https://img.shields.io/badge/Forge-1.20.1-e04e14?style=for-the-badge" />
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

---

## 🧩 Compatibility & Requirements

- **Minecraft Version:** 1.20.1
- **Loaders:** Fabric, Forge
- **Java:** 17+
- **Client Config File:** `config/conditionalvideos.json`
- **Dedicated Server Config File:** `config/conditionalvideos-server.json`
- **Required Fabric API** (Fabric only)
- **Required** [**WATERMeDIA: Multimedia API v3**](https://modrinth.com/mod/watermedia) (client side)
- **Required** [**WATERMeDIA: Native Binaries**](https://modrinth.com/mod/wm_binaries) (client side, ships with WATERMeDIA)
- **Optional** [**WATERMeDIA: YouTube Extension**](https://modrinth.com/mod/watermedia_youtube_extension) — required only if your config uses YouTube URLs

---

## 🧭 Client vs Server (Important)


### Client Instance

- Creates and uses:
  - `config/conditionalvideos.json`
- In **singleplayer**:
  - Uses local client config directly.
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

- Applies to **every** condition (`firstJoin`, `playerDeath`, `deathByEntity`, `entityKilled`, `advancementCompleted`, `dimensionChanged`) — not just the first-join cinematic.
- For `firstJoin` specifically, the server also puts the player in Spectator from the moment the loading screen appears until the playback finishes, so the world is invisible and silent during the wait. Other conditions only show the overlay; gameplay keeps running underneath them, matching the existing behavior of the playback screen.
- URL/YouTube sources skip the loading screen — WATERMeDIA handles buffering directly inside the playback screen.
- Already-cached files skip the loading screen and jump straight to playback.
- If the server is unreachable or the download stalls, the loading screen times out after about a minute and the player resumes normal gameplay.

---

## 🎯 Supported Conditions

Supports multiple gameplay trigger types so you can tailor cinematic playback to meaningful moments during progression.

- **First connection trigger** (`firstJoin`)
  Plays an intro/cinematic when the player enters the world/session.


- **General death trigger** (`playerDeath`)
  Works as the default fallback cinematic for any player death event.


- **Death by entity trigger** (`deathByEntity`)
  Lets you override the generic death behavior with custom videos based on the killer entity ID (`namespace:entity`), such as `minecraft:zombie` or `minecraft:creeper`.
  When this trigger matches, it takes precedence over the general death trigger.


- **Entity kill trigger** (`entityKilled`)
  Plays a video when the player defeats specific configured entity IDs.


- **Advancement completion trigger** (`advancementCompleted`)
  Plays a video when configured advancements are completed.


- **Dimension transition trigger** (`dimensionChanged`)
  Plays a video when the player enters configured dimensions.

---

## 🧱 Condition Object Schema

Each condition entry now holds a **playlist** under `videos: [...]` plus a few shared flags. Each item in `videos` is a `VideoEntry` describing a single clip plus its overlay/skip/loop/transition options.

### Shared condition fields

| Property | Type | Required | Description |
|---|---|---|---|
| `repeatableInSameSession` | `boolean` | Yes | If `false`, this condition key can trigger once per session. If `true`, it can trigger repeatedly during the same session. |
| `playlistLoop` | `boolean` | No | When `true`, after the last entry finishes the playlist restarts from the first. Default `false`. |
| `videos` | `VideoEntry[]` | Yes | One or more entries played in order. A playlist of length 1 behaves like the legacy single-video setup. |

### `VideoEntry` fields

| Property | Type | Required | Description |
|---|---|---|---|
| `source` | `string` | Yes | Either a path to a local video file (relative to the Minecraft game directory or absolute) **or** a URL (`http://`, `https://`, YouTube link). |
| `skippable` | `boolean` | No | If `true`, the user can skip playback with `ESC`. Default `true`. Forced to `true` when `videoLoop = true`. |
| `videoLoop` | `boolean` | No | If `true`, the entry loops seamlessly until the player skips it with `ESC`. Default `false`. Requires `skippable = true`. |
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

## ⌨️ In-Game Controls

While a video is playing:

- **Tap `ESC`** → skip to the next playlist entry (or close the playback if it is the last one). Looped entries are interrupted as well.
- **Hold `ESC` for ~1 second, then release** → close the playback screen entirely without queuing the next entry. The release-after-hold behavior prevents the in-world pause menu from toggling repeatedly if you keep the key pressed.
- The mouse cursor auto-hides after a few seconds of inactivity and reappears the moment the mouse moves.
- Vanilla Minecraft sounds are muted while a video (or the multiplayer loading screen) is on screen.

`skippable = false` disables both the tap and hold behaviors for that entry; the player must wait for it to finish or for the playlist to advance via `nextAt` / `videoLoop = false`.

---

## 🌍 Localization

UI strings (`Press ESC to skip`, `Loading video…`) ship in four locales out of the box:

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

The on-disk schema is versioned via `configVersion`. Configs created by ConditionalVideos 1.1.x (with the legacy `video: "..."` field per condition) are migrated automatically the first time 1.2.0 reads them:

1. A backup of the original file is written next to it as `conditionalvideos.json.bak-v<previousVersion>` (or `conditionalvideos-server.json.bak-v...`).
2. Each legacy condition's `video` string and decorator fields are folded into a single-entry `videos: [...]` playlist.
3. The file is rewritten with `configVersion: 2`.

If you want to revert, the `.bak-v1` file is left untouched and you can restore it manually.

---

## 🗂️ Full `config/conditionalvideos.json` or `config/conditionalvideos-server.json` Example

```json
{
  "configVersion": 2,
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
        "descriptionTextScale": 1.0,
        "textBoxOpacity": 0.5,
        "videoVolume": 0.8,
        "nextAt": 8.0
      },
      {
        "source": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "skippable": true,
        "enableBackground": true,
        "colorBackground": "#FF000000",
        "videoVolume": 1.0,
        "transition": "fadeOut/In"
      }
    ]
  },
  "playerDeath": {
    "repeatableInSameSession": true,
    "playlistLoop": false,
    "videos": [
      {
        "source": "videos/death/default_death.mp4",
        "skippable": true,
        "enableBackground": true,
        "colorBackground": "#AA000000",
        "videoTitle": "&cYou Died",
        "videoTitlePosition": "bottomLeft",
        "videoDescription": "&7Try again",
        "videoDescriptionPosition": "bottomLeft"
      }
    ]
  },
  "entityKilled": {
    "minecraft:skeleton": {
      "repeatableInSameSession": true,
      "videos": [
        {
          "source": "videos/kills/skeleton.mp4",
          "skippable": true,
          "enableBackground": false,
          "colorBackground": "#000000",
          "videoTitle": "&fSkeleton Defeated",
          "videoTitlePosition": "topRight",
          "videoDescription": "&7Nice shot",
          "videoDescriptionPosition": "topRight"
        }
      ]
    },
    "minecraft:warden": {
      "repeatableInSameSession": false,
      "videos": [
        {
          "source": "videos/kills/warden.mp4",
          "skippable": false,
          "enableBackground": true,
          "colorBackground": "#CC000000",
          "videoTitle": "&5&lEpic Victory",
          "videoTitlePosition": "topRight",
          "videoDescription": "&dYou defeated the Warden",
          "videoDescriptionPosition": "topRight",
          "titleTextScale": 1.6
        }
      ]
    }
  },
  "deathByEntity": {
    "minecraft:zombie": {
      "repeatableInSameSession": true,
      "videos": [
        {
          "source": "videos/death/zombie_death.mp4",
          "skippable": true,
          "enableBackground": true,
          "colorBackground": "#B0000000",
          "videoTitle": "&2A Zombie got you",
          "videoTitlePosition": "bottomRight",
          "videoDescription": "&7Watch your back at night",
          "videoDescriptionPosition": "bottomRight"
        }
      ]
    },
    "minecraft:creeper": {
      "repeatableInSameSession": false,
      "videos": [
        {
          "source": "videos/death/creeper_death.mp4",
          "skippable": true,
          "enableBackground": true,
          "colorBackground": "#99000000",
          "videoTitle": "&aBoom...",
          "videoTitlePosition": "bottomRight",
          "videoDescription": "&7A Creeper surprised you",
          "videoDescriptionPosition": "bottomRight"
        }
      ]
    }
  },
  "advancementCompleted": {
    "minecraft:story/mine_diamond": {
      "repeatableInSameSession": false,
      "videos": [
        {
          "source": "videos/advancements/diamond.mp4",
          "skippable": true,
          "enableBackground": false,
          "colorBackground": "#000000",
          "videoTitle": "&bDiamonds!",
          "videoTitlePosition": "topLeft",
          "videoDescription": "&fFirst diamond obtained",
          "videoDescriptionPosition": "topLeft"
        }
      ]
    },
    "minecraft:nether/root": {
      "repeatableInSameSession": false,
      "videos": [
        {
          "source": "videos/advancements/nether_entry.mp4",
          "skippable": true,
          "enableBackground": true,
          "colorBackground": "#AA1A0000",
          "videoTitle": "&4Welcome to the Nether",
          "videoTitlePosition": "topLeft",
          "videoDescription": "&7Temperature rising",
          "videoDescriptionPosition": "topLeft"
        }
      ]
    }
  },
  "dimensionChanged": {
    "minecraft:the_nether": {
      "repeatableInSameSession": true,
      "playlistLoop": true,
      "videos": [
        {
          "source": "videos/dimensions/nether_loop.mp4",
          "skippable": true,
          "videoLoop": true,
          "enableBackground": true,
          "colorBackground": "#CC000000",
          "videoTitle": "&cEntered the Nether",
          "videoTitlePosition": "bottomLeft",
          "videoDescription": "&7Press ESC when you are ready",
          "videoDescriptionPosition": "bottomLeft"
        }
      ]
    },
    "minecraft:the_end": {
      "repeatableInSameSession": false,
      "videos": [
        {
          "source": "videos/dimensions/end.mp4",
          "skippable": false,
          "enableBackground": true,
          "colorBackground": "#CC000000",
          "videoTitle": "&5The End awaits",
          "videoTitlePosition": "bottomLeft",
          "videoDescription": "&dPrepare for the dragon",
          "videoDescriptionPosition": "bottomLeft"
        }
      ]
    }
  },
  "consumedConditionSessions": []
}
```

---

## 🛠️ Support

- Found a bug or want to request a feature?

  [<img alt="Report Issues" src="https://img.shields.io/badge/Report-Issues-red?style=for-the-badge&logo=github&logoColor=white" />](https://github.com/MateoF024/conditionalvideos)
