<div align="center">

![conditional_videos.png](libs/conditional_videos.png)

Play custom videos when specific in-game events are detected — first joins, deaths, kills, advancements, item milestones, scoreboard thresholds, and more.

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/conditionalvideos?style=for-the-badge&logo=modrinth&label=Modrinth&color=00AF5C&logoColor=white)](https://modrinth.com/mod/conditionalvideos) [![CurseForge Downloads](https://img.shields.io/curseforge/dt/1521845?style=for-the-badge&logo=curseforge&label=CurseForge&color=f16a20&logoColor=white)](https://www.curseforge.com/minecraft/mc-mods/conditionalvideos)

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20%7C%201.21.1%20%7C%201.21.11%20%7C%2026.1.2%20%7C%2026.2-3c8527?style=for-the-badge) ![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-dbd0b4?style=for-the-badge)

</div>

---

## ✨ Overview

Choose a condition, add videos to its file, and the mod plays them at the right moment. It works in singleplayer, on LAN and on dedicated servers, where the server decides what plays and sends its video files to the players.

- **Sources:** local files, direct URLs, YouTube, Twitch, Kick, TikTok, SoundCloud and more, through WATERMeDIA.
- **Playlists:** transitions, loops, text overlays, per-video volume, start and end times.
- **Waiting room:** players wait for each other on `firstJoin`, then watch together.
- **Safe to watch:** players are protected during videos, and the world can freeze while everyone is watching.
- **Commands** to play, stop, pause and edit videos in-game, plus an API for other mods.

---

## 🧩 Requirements

| Minecraft | Loaders | Java |
|---|---|---|
| 1.20.1 | Fabric, Forge | 17+ |
| 1.21.1 | Fabric, NeoForge | 21+ |
| 1.21.11 | Fabric, NeoForge | 21+ |
| 26.1.2 | Fabric, NeoForge | 25+ |
| 26.2 | Fabric, NeoForge | 25+ |

**Everywhere:**

- [Architectury API](https://www.curseforge.com/minecraft/mc-mods/architectury-api)
- [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) (Fabric only)

**On the client:**

- [WATERMeDIA: Multimedia API](https://www.curseforge.com/minecraft/mc-mods/watermedia) `3.0.0`+
- [WATERMeDIA: Native Binaries](https://www.curseforge.com/minecraft/mc-mods/watermedia-binaries) `3.0.0`+

> On **26.2**, videos play on both renderers, but Vulkan support is **experimental**. OpenGL is the safest choice.

---

## 🎯 Conditions

| Condition | Plays when |
|---|---|
| `firstJoin` | The player joins the world. It always plays before anything else. |
| `playerDeath` | The player dies. |
| `totemUsed` | A Totem of Undying saves the player. |
| `bedSleep` | The player starts sleeping. |
| `deathByEntity` | A specific entity kills the player. Takes priority over `playerDeath`. |
| `entityKilled` | The player kills a specific entity. |
| `advancementCompleted` | The player completes an advancement. |
| `dimensionChanged` | The player enters a dimension. |
| `itemObtained` | The player gets an item. |
| `itemCrafted` | The player crafts an item. |
| `recipeUnlocked` | The player unlocks a recipe. |
| `scoreboard` | A score meets a comparison. |
| `worldPosition` | The player enters or leaves an area. |
| `custom` | A command or another mod fires it. |

---

## ⌨️ Commands

For operators (permission level 2+), command blocks and the server console:

```
/conditionalvideos play <targets> <condition> [delay <seconds>] [exclude <players>] [excludeteam <team>] [excludeops] [once] [sync]
/conditionalvideos stop <targets>
/conditionalvideos pause <targets>
/conditionalvideos reset <targets> <condition | all>

/conditionalvideos set defaultvolume <volume>
/conditionalvideos set volume <condition> <video_index> <volume>
/conditionalvideos set conditiondelay <condition> <seconds>
/conditionalvideos set startat <condition> <video_index> <seconds>
/conditionalvideos set title <condition> <video_index> [at_position <x> <y>] <text>
/conditionalvideos set description <condition> <video_index> [at_position <x> <y>] <text>
/conditionalvideos set titleposition <condition> <video_index> <position | x y>
/conditionalvideos set descriptionposition <condition> <video_index> <position | x y>

/conditionalvideos waitingroom start [force]
/conditionalvideos waitingroom join [targets]
/conditionalvideos waitingroom leave [targets]
/conditionalvideos waitingroom add <targets>
/conditionalvideos waitingroom remove <targets>
```

---

## 🗂️ Configuration

Each condition is a file:

```
config/conditionalvideos/
├─ conditions/          singleplayer and LAN host
│  ├─ firstJoin.json
│  ├─ entityKilled/minecraft/warden.json
│  ├─ advancementCompleted/minecraft/story/mine_diamond.json
│  ├─ scoreboard/kills.json
│  └─ worldPosition/spawn_area.json
└─ server-conditions/   dedicated server, same layout
```

Changes apply right away, also for connected players. The mod adds any missing option with its default at the top of the file and never changes what you wrote. A broken file is left untouched and reported in the log and in the chat. Configs from older versions migrate on the first launch and the originals stay as `.bak`.

```json
{
  "repeatableInSameSession": false,
  "videos": [
    {
      "source": "videos/intro.mp4",
      "videoTitle": "&6&lWelcome",
      "videoVolume": 0.8,
      "nextAt": 8.0
    },
    {
      "source": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      "transition": "fadeOut/In"
    }
  ]
}
```

### Condition options

| Option | Default | Description |
|---|---|---|
| `videos` | `[]` | The videos, played in order. |
| `repeatableInSameSession` | `false` on `firstJoin`, `true` on the rest | `false` plays once per player and world. |
| `playlistLoop` | `false` | Restarts the playlist when it ends. |
| `conditionDelay` | `0.0` | Seconds to wait before playing. |
| `replayOnReconnect` | `false` | Plays an interrupted video again from the start on reconnect. |
| `disableLoadingScreen` | `false` | The first video gets ready in the background and appears once it has a picture. |
| `protectPlayer` | `true` | No damage, hunger, pickups or pushing during the video. |
| `freezeWorld` | `true` on `firstJoin`, `false` on the rest | The world stops while every connected player is watching. |
| `excludePlayers`, `excludeTeams` | `[]` | Players and teams that never see this condition. |
| `excludeOps` | `false` | Operators never see this condition. |

**`scoreboard`** adds `value` (default `0`) and `comparator`: `equal`, `less`, `greater`, `lessOrEqual` or `greaterOrEqual` (default). It fires when the comparison becomes true.

**`worldPosition`** adds the area. Without `y` it covers any height, and `trigger` is `enter` or `exit`:

```json
{
  "dimension": "minecraft:overworld",
  "from": { "x": -20, "z": -20 },
  "to": { "x": 20, "z": 20 },
  "trigger": "enter",
  "videos": [ { "source": "videos/zone.mp4" } ]
}
```

### Video options

| Option | Default | Description |
|---|---|---|
| `source` | Required | Local path (relative to the game folder or absolute) or URL. |
| `skippable` | `true` | Whether the player can skip it. |
| `videoLoop` | `false` | Loops until skipped. Forces `skippable`. |
| `enableBackground` | `true` | Draws a solid color behind the video. |
| `colorBackground` | `#000000` | `#RRGGBB` or `#AARRGGBB`. |
| `videoTitle`, `videoDescription` | Empty | Text with color codes (`&6`, `&l`). `\n` breaks the line. |
| `videoTitlePosition`, `videoDescriptionPosition` | `bottomLeft` | `topLeft`, `topRight`, `bottomLeft`, `bottomRight`, `center` or `X/Y` in pixels. Negative values measure from the right and bottom. |
| `videoTitleAlignment`, `videoDescriptionAlignment` | `left` | `left`, `center` or `right`. |
| `titleTextScale`, `descriptionTextScale` | `1.0` | Text size. |
| `textBoxOpacity` | Automatic | `0.0`–`1.0`, opacity of the box behind the text. |
| `videoVolume` | `defaultVolume` | `0.0`–`1.0`. |
| `transition` | `cut` | How it enters from the previous video: `cut` or `fadeOut/In`. |
| `startAt` | `0.0` | Second where it starts. |
| `nextAt` | Video end | Second where it moves on to the next video. |

### Waiting room

It is the `waitingRoom` block in `firstJoin.json`. Players wait covered by the background until the room is ready, then watch together. A singleplayer or LAN host doesn't enter unless they use `waitingroom join`.

| Option | Default | Description |
|---|---|---|
| `enabled` | `false` | Turns the room on. |
| `requiredPlayers` | `0` | Players needed. `0` means the server's maximum. |
| `requiredPlayerNames`, `requiredTeams` | `[]` | If set, only these players enter and count. |
| `startMode` | `auto` | `auto` starts by itself. `op` waits for an operator, who can also press Space. |
| `allowLeave` | `false` | Lets players leave with the skip key. |
| `allowChat` | `false` | Lets players chat. Operators always can. |
| `background` | `#000000` | `#RRGGBB`, or the path or URL of an image or GIF. |
| `title`, `description`, `counter` | Only `counter` is set | Room texts. They accept `%current_players%`, `%required_players%` and `%missing_players%`. |
| `titlePosition`, `titleAlignment`, `titleScale` | `center`, `center`, `1.0` | Same for `description` and `counter`. |

### Common options

`config/conditionalvideos-common.json`:

| Option | Default | Description |
|---|---|---|
| `videoQuality` | `AUTO` | Or `LOWEST`, `LOWER`, `LOW`, `LOWISH`, `MEDIUM`, `HIGH`, `HIGHER`, `HIGHEST`, `ULTRA_8K`. |
| `defaultVolume` | `1.0` | Volume of videos without their own. |
| `videoLoadTimeoutSeconds` | `180` | How long to wait for a video to start. Minimum `30`. |
| `alwaysShowCursor` | `false` | Never hides the cursor during a video. |
| `allowGameSounds` | `false` | Keeps other sounds playing during videos. |
| `blockMatureContent` | `true` | Blocks mature sources. Each player's own choice. |
| `debugLogging` | `false` | Detailed log, for troubleshooting. Each installation's own choice. |

On a server, it sets the first five for its players.

---

## 🎮 Skipping videos

The skip key is `ESC` until you bind *Skip video* in Options → Controls.

- **Tap** to skip to the next video.
- **Hold** until the bar fills, then **release** to skip the whole playlist.

---

## 🌍 Languages and API

Available in English and Spanish (`es_es`, `es_ar`, `es_mx`). Other mods can add custom conditions and control playback through the API in `org.mateof24.conditionalvideos.api`.

---

## 📜 License

[ConditionalVideos License](LICENSE.txt): free for any server and any free modpack. Charging for anything that uses the mod requires permission.

---

<div align="center">

[![Wiki](https://img.shields.io/badge/Docs-Wiki-0969da?style=for-the-badge&logo=github&logoColor=white)](https://github.com/MateoF024/conditionalvideos/wiki) [![Issues](https://img.shields.io/badge/Report-Issues-red?style=for-the-badge&logo=github&logoColor=white)](https://github.com/MateoF024/conditionalvideos/issues)

</div>
