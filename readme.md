<div align="center">

# 🎬 ConditionalVideos

Server-aware cinematic triggers for Minecraft. Configure conditions once and let clients play synced videos — local files, URLs, or full playlists — exactly when it matters.

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/conditionalvideos?style=for-the-badge&logo=modrinth&label=Modrinth&color=00AF5C&logoColor=white)](https://modrinth.com/mod/conditionalvideos) [![CurseForge Downloads](https://img.shields.io/curseforge/dt/1521845?style=for-the-badge&logo=curseforge&label=CurseForge&color=f16a20&logoColor=white)](https://www.curseforge.com/minecraft/mc-mods/conditionalvideos)

[![Fabric](https://img.shields.io/badge/Fabric-1.20.1%20%7C%201.21.1%20%7C%201.21.11-dbd0b4?style=for-the-badge)](https://fabricmc.net/) [![Forge 1.20.1](https://img.shields.io/badge/Forge-1.20.1-e04e14?style=for-the-badge)](https://minecraftforge.net/) [![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1%20%7C%201.21.11-f98010?style=for-the-badge)](https://neoforged.net/) [![Environment](https://img.shields.io/badge/Env-Client%20%26%20Server-4a90d9?style=for-the-badge)](https://modrinth.com/mod/conditionalvideos)

[![Wiki](https://img.shields.io/badge/Docs-Wiki-0969da?style=for-the-badge&logo=github&logoColor=white)](https://github.com/MateoF024/conditionalvideos/wiki) [![Issues](https://img.shields.io/badge/Report-Issues-red?style=for-the-badge&logo=github&logoColor=white)](https://github.com/MateoF024/conditionalvideos/issues)

</div>

---

## ✨ Overview

ConditionalVideos plays custom videos when specific in-game events are detected — first joins, deaths, kills, advancements, item milestones, scoreboard thresholds, and more.

It runs on **both client and dedicated server**: the server detects conditions and tells the right client what to play, while the client handles the actual playback. This makes it a good fit for servers, story maps, quest packs, and roleplay setups where cinematic feedback improves immersion.

**Highlights**

- A dozen trigger types, from `firstJoin` to scoreboard thresholds and mod-defined custom conditions.
- Per-condition **playlists** with crossfade/cut transitions, looping, text overlays, per-clip volume, and timed cuts.
- Broad source support through **WATERMeDIA** — local files, direct URLs, YouTube in high quality, Twitch/Kick clips, TikTok, SoundCloud, and many more.
- A **playback queue** so overlapping triggers play in sequence instead of cutting each other off.
- A small **public API** for other mods (see the project Wiki).

---

## 🧩 Compatibility & Requirements

| Minecraft | Loaders | Java |
|---|---|---|
| 1.20.1 | Fabric, Forge | 17+ |
| 1.21.1 | Fabric, NeoForge | 21+ |
| 1.21.11 | Fabric, NeoForge | 21+ |

**Required (client side):**

- [WATERMeDIA: Multimedia API](https://modrinth.com/mod/watermedia) — **v3.0.0.21+**
- [WATERMeDIA: Native Binaries](https://modrinth.com/mod/watermedia-binaries) — **v3.0.0-rc.4+**
- [Fabric API](https://modrinth.com/mod/fabric-api) — **any** (Fabric Only)

**Config files**:

- `config/conditionalvideos.json` — client / singleplayer rules
- `config/conditionalvideos-server.json` — authoritative rules for a dedicated server
- `config/conditionalvideos-common.json` — client playback behavior (also read by servers for the synced options)

In multiplayer the dedicated server owns the config, ships any local video files to clients automatically (cached and reused on reconnect), and URLs are streamed directly by each client. Treat `conditionalvideos-server.json` as the source of truth for server content.

---

## 🎯 Supported Conditions

Each condition is configured with a **playlist**. Simple conditions use a single config key; keyed conditions are a map of `id → condition`.

**Simple** (single key):

- `firstJoin` — plays when the player enters the world/session. **Always prioritized**: if other conditions fire during world load, `firstJoin` plays first and the rest queue behind it.
- `playerDeath` — fallback cinematic for any death.
- `totemUsed` — a Totem of Undying saves the player.
- `bedSleep` — the player starts sleeping in a bed.

**Keyed** (map of `id → condition`):

- `deathByEntity` — death by a specific killer entity ID (overrides `playerDeath`).
- `entityKilled` — the player defeats a configured entity ID.
- `advancementCompleted` — a configured advancement is completed.
- `dimensionChanged` — the player enters a configured dimension.
- `itemObtained` — the player obtains a configured item (pickup, craft, or smelt result).
- `itemCrafted` — a configured item is actually crafted (independent from `itemObtained`).
- `recipeUnlocked` — a configured recipe is newly unlocked in the recipe book.
- `scoreboard` — a player's score on an objective satisfies a comparison (see below).
- `custom` — no built-in detector; fired only by the public API or the `play` command.

### Scoreboard comparators

Each `scoreboard` entry compares a player's score against a `value` using a `comparator`:

| `comparator` | Fires when |
|---|---|
| `equal` | `score == value` |
| `less` | `score < value` |
| `greater` | `score > value` |
| `lessOrEqual` | `score <= value` |
| `greaterOrEqual` | `score >= value` (default) |

It is **edge-triggered**: a satisfied comparison fires once and only re-arms after it turns false and crosses back to true. Because scores persist with the world, a comparison already satisfied before logout does not re-fire on rejoin.

---

## 🗂️ Configuring `conditionalvideos.json`

Each condition entry holds a **playlist** under `videos: [...]` plus a few shared flags. Each item in `videos` is a `VideoEntry` describing one clip and its overlay / skip / loop / transition options. Only `firstJoin` ships preconfigured; every other condition starts as an empty `{}` until you add videos.

### Shared condition fields

| Property | Type | Required | Description |
|---|---|---|---|
| `repeatableInSameSession` | `boolean` | Yes | If `false`, the condition can trigger once per session. If `true`, it can trigger repeatedly. |
| `playlistLoop` | `boolean` | No | When `true`, the playlist restarts from the first entry after the last finishes. Default `false`. |
| `videos` | `VideoEntry[]` | Yes | One or more entries played in order. Length 1 behaves like a single-video setup. |

`scoreboard` entries add two fields **at the condition level**: `value` (`int`, required — the threshold) and `comparator` (`string`, optional — default `greaterOrEqual`).

### `VideoEntry` fields

| Property | Type | Required | Description |
|---|---|---|---|
| `source` | `string` | Yes | A path to a local video file (relative to the game directory or absolute) **or** a URL (`http://`, `https://`, YouTube link). |
| `skippable` | `boolean` | No | If `true`, the user can skip playback. Default `true`. Forced to `true` when `videoLoop = true`. |
| `videoLoop` | `boolean` | No | If `true`, the entry loops seamlessly until skipped. Default `false`. Requires `skippable = true`. |
| `enableBackground` | `boolean` | No | Draws a solid-color full-screen background behind the video. Default `true`. |
| `colorBackground` | `string` | No | Hex color in `#RRGGBB` or `#AARRGGBB`. Default `#000000`. |
| `videoTitle` | `string` | No | Optional title overlay. Supports legacy format codes (`&6`, `&l`, `&r`, …). |
| `videoTitlePosition` | `string` | No | `topLeft`, `topRight`, `bottomLeft`, `bottomRight`. Default `bottomLeft`. |
| `videoDescription` | `string` | No | Optional description overlay. Supports legacy format codes. |
| `videoDescriptionPosition` | `string` | No | `topLeft`, `topRight`, `bottomLeft`, `bottomRight`. Default `bottomLeft`. |
| `titleTextScale` | `float` | No | Multiplier applied to the title font. Default `1.0`. |
| `descriptionTextScale` | `float` | No | Multiplier applied to the description font. Default `1.0`. |
| `textBoxOpacity` | `float` | No | `0.0`–`1.0` opacity of the box behind the title/description. Omit for the legacy auto alpha. |
| `videoVolume` | `float` | No | `0.0`–`1.0` volume for this entry. Default `1.0`. |
| `transition` | `string` | No | Transition applied **when entering this entry** from the previous one. `cut` (default) or `fadeOut/In`. Ignored for the first entry. |
| `nextAt` | `float` | No | Time in seconds (from the start of this entry) at which to cut/transition to the next entry. Omit to wait for the video to end naturally. |

> `transition` is a property of the *incoming* entry: if `videos[1].transition = "fadeOut/In"`, the player fades `videos[0]` out while fading `videos[1]` in. `cut` is an instant frame-perfect swap.

### Example

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

> **Config safety:** a single malformed entry never wipes the file. Entries are parsed leniently — a bad one is skipped and logged, while every valid entry is kept. On a JSON syntax error the file is left untouched and defaults are used only in memory for that session, so you can fix the typo without losing your work.

---

## ⚙️ Common Configuration (`conditionalvideos-common.json`)

Controls client playback behavior. Created automatically with safe defaults.

| Property | Type | Default | Description |
|---|---|---|---|
| `videoQuality` | `string` | `AUTO` | `AUTO` picks the best available stream. Any other value (`LOWEST`, `LOW`, `MEDIUM`, `HIGH`, `HIGHEST`) forces that quality. Only affects multi-variant sources (YouTube and similar). |
| `alwaysShowCursor` | `boolean` | `false` | When `true`, the playback screen never auto-hides the mouse cursor. |
| `allowGameSounds` | `boolean` | `false` | When `true`, vanilla Minecraft sounds keep playing during a video (muted by default). |
| `blockMatureContent` | `boolean` | `true` | When `true`, mature-content sources are blocked. **Client-only** — a server cannot override it. |
| `debugLogging` | `boolean` | `false` | When `true`, emits detailed `[CV/...]` diagnostics. Leave off for normal play; turn on only to troubleshoot. |

> In multiplayer, `videoQuality`, `alwaysShowCursor` and `allowGameSounds` come from the server's common config when available; `blockMatureContent` and `debugLogging` are always read from the local client file.

---

## ⌨️ In-Game Controls

By default the **skip key is unbound**, which falls back to `ESC`. You can bind a dedicated key under Options → Controls → *Conditional Videos*; that key then becomes the only active skip key and the on-screen hint always shows its name.

- **Tap** the skip key → skip to the next playlist entry (or close playback if it is the last one). Looped entries are interrupted too.
- **Hold** the skip key → a short white bar fills; **release after it is full** to skip the whole playlist (close playback). A "release to skip the playlist" message appears when the bar is full. Releasing **before** the bar fills cancels and skips nothing.
- `skippable = false` disables both gestures for that entry and hides the skip hint — the player waits for it to finish or for the playlist to advance.

Other behavior: the cursor auto-hides after a few seconds of inactivity (unless `alwaysShowCursor`), vanilla sounds are muted while a video is on screen (unless `allowGameSounds`), and toast notifications are hidden during playback.

---

## 🧑‍⚖️ Server Commands

Available to **permission level 2+** (command blocks, OPs) and the server console:

```
/conditionalvideos play <targets> <condition>
/conditionalvideos stop <targets>
/conditionalvideos pause <targets>
```

- **`play`** forces a condition to play on each target, ignoring the once-per-session gate. `<condition>` auto-completes with keys that have at least one video.
- **`stop`** closes active playback and clears the pending queue.
- **`pause`** toggles pause/resume on the current video.

Condition keys use a bare key for simple conditions (`firstJoin`, `playerDeath`, `totemUsed`, `bedSleep`) or a `type/key` form for keyed ones, e.g. `entityKilled/minecraft:warden`, `advancement/minecraft:story/mine_diamond`, `dimension/minecraft:the_nether`, `scoreboard/kills`, `custom/my_event`.

> The command/API keys for advancement and dimension use the `advancement/...` and `dimension/...` prefixes, while the **config JSON** stores them under the `advancementCompleted` and `dimensionChanged` maps.

---

## 🌍 Localization

UI and command strings ship in `en_us`, `es_es`, `es_ar` and `es_mx`. Add more by dropping a `<locale>.json` into a resource pack under `assets/conditionalvideos/lang/`.

---

## 🧰 Developer API

Other mods can register **custom conditions** and **trigger / control playback** through a small, stable public API in the `org.mateof24.conditionalvideos.api` package.
