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
  Client-side cinematic triggers for Minecraft. Configure a condition, assign a video, and play it exactly when it matters.
</p>

---

## ✨ Overview

ConditionalVideos is a client-side mod that plays custom videos when specific in-game events are detected.

It is designed for curated gameplay experiences such as servers, story maps, quest packs, and roleplay setups where cinematic feedback improves immersion.

---

## 🧩 Compatibility & Requirements

- **Minecraft Version:** 1.20.1
- **Loaders:** Fabric, Forge
- **Java:** 17+
- **Config File:** `config/conditionalvideos.json`
- **Required Fabric API**
- **Required** [**WATERMeDIA: Multimedia API**](https://modrinth.com/mod/watermedia)

---

## 🎯 Supported Conditions

Supports multiple gameplay trigger types so you can tailor cinematic playback to meaningful moments during progression.

- **First connection trigger**  
  Plays an intro/cinematic when the player enters the world/session.


- **General death trigger**  
  Works as the default fallback cinematic for any player death event.


- **Entity-specific death trigger**  
  Lets you override the generic death behavior with custom videos based on the killer entity ID (`namespace:entity`), such as `minecraft:zombie` or `minecraft:creeper`.  
  When this trigger matches, it takes precedence over the general death trigger.


- **Entity kill trigger**  
  Plays a video when the player defeats specific configured entity IDs.


- **Advancement completion trigger**  
  Plays a video when configured advancements are completed.


- **Dimension transition trigger**  
  Plays a video when the player enters configured dimensions.

---

## 🧱 Condition Object Schema

Each condition entry shares the same structure:

| Property | Type | Required | Description |
|---|---|---|---|
| `video` | `string` | Yes | Path to the video file. Relative paths are resolved from the Minecraft game directory; absolute paths are also allowed. |
| `skippable` | `boolean` | Yes | If `true`, the user can skip playback with `ESC`. |
| `repeatableInSameSession` | `boolean` | Yes | If `false`, this condition key can trigger once per session. If `true`, it can trigger repeatedly during the same session. |
| `enableBackground` | `boolean` | Yes | Enables a solid-color full-screen background behind the video. |
| `colorBackground` | `string` | Yes* | Hex color in `#RRGGBB` or `#AARRGGBB` format. |
| `videoTitle` | `string` | No | Optional title overlay. Supports legacy Minecraft format codes (`&6`, `&l`, `&r`, etc.). |
| `videoTitlePosition` | `string` | No | `topLeft`, `topRight`, `bottomLeft`, `bottomRight`. |
| `videoDescription` | `string` | No | Optional description overlay. Supports legacy format codes. |
| `videoDescriptionPosition` | `string` | No | `topLeft`, `topRight`, `bottomLeft`, `bottomRight`. |

---

## Session Tracking Field

The config includes one internal tracking field managed by the mod:

- **`consumedConditionSessions`**
    - Stores condition/session keys that were already consumed when `repeatableInSameSession` is `false`.
    - Prevents non-repeatable conditions from replaying in the same session.

> You usually should not edit this field manually unless you intentionally want to reset session trigger history for testing.

---

## 🗂️ Full `config/conditionalvideos.json` Example

```json
{
  "firstJoin": {
    "video": "videos/intro.mp4",
    "skippable": true,
    "repeatableInSameSession": false,
    "enableBackground": true,
    "colorBackground": "#FF000000",
    "videoTitle": "&6&lWelcome",
    "videoTitlePosition": "topLeft",
    "videoDescription": "&fEnjoy your journey",
    "videoDescriptionPosition": "topLeft"
  },
  "playerDeath": {
    "video": "videos/death/default_death.mp4",
    "skippable": true,
    "repeatableInSameSession": true,
    "enableBackground": true,
    "colorBackground": "#AA000000",
    "videoTitle": "&cYou Died",
    "videoTitlePosition": "bottomLeft",
    "videoDescription": "&7Try again",
    "videoDescriptionPosition": "bottomLeft"
  },
  "entityKilled": {
    "minecraft:skeleton": {
      "video": "videos/kills/skeleton.mp4",
      "skippable": true,
      "repeatableInSameSession": true,
      "enableBackground": false,
      "colorBackground": "#000000",
      "videoTitle": "&fSkeleton Defeated",
      "videoTitlePosition": "topRight",
      "videoDescription": "&7Nice shot",
      "videoDescriptionPosition": "topRight"
    },
    "minecraft:warden": {
      "video": "videos/kills/warden.mp4",
      "skippable": false,
      "repeatableInSameSession": false,
      "enableBackground": true,
      "colorBackground": "#CC000000",
      "videoTitle": "&5&lEpic Victory",
      "videoTitlePosition": "topRight",
      "videoDescription": "&dYou defeated the Warden",
      "videoDescriptionPosition": "topRight"
    }
  },
  "deathByEntity": {
    "minecraft:zombie": {
      "video": "videos/death/zombie_death.mp4",
      "skippable": true,
      "repeatableInSameSession": true,
      "enableBackground": true,
      "colorBackground": "#B0000000",
      "videoTitle": "&2A Zombie got you",
      "videoTitlePosition": "bottomRight",
      "videoDescription": "&7Watch your back at night",
      "videoDescriptionPosition": "bottomRight"
    },
    "minecraft:creeper": {
      "video": "videos/death/creeper_death.mp4",
      "skippable": true,
      "repeatableInSameSession": false,
      "enableBackground": true,
      "colorBackground": "#99000000",
      "videoTitle": "&aBoom...",
      "videoTitlePosition": "bottomRight",
      "videoDescription": "&7A Creeper surprised you",
      "videoDescriptionPosition": "bottomRight"
    }
  },
  "advancementCompleted": {
    "minecraft:story/mine_diamond": {
      "video": "videos/advancements/diamond.mp4",
      "skippable": true,
      "repeatableInSameSession": false,
      "enableBackground": false,
      "colorBackground": "#000000",
      "videoTitle": "&bDiamonds!",
      "videoTitlePosition": "topLeft",
      "videoDescription": "&fFirst diamond obtained",
      "videoDescriptionPosition": "topLeft"
    },
    "minecraft:nether/root": {
      "video": "videos/advancements/nether_entry.mp4",
      "skippable": true,
      "repeatableInSameSession": false,
      "enableBackground": true,
      "colorBackground": "#AA1A0000",
      "videoTitle": "&4Welcome to the Nether",
      "videoTitlePosition": "topLeft",
      "videoDescription": "&7Temperature rising",
      "videoDescriptionPosition": "topLeft"
    }
  },
  "dimensionChanged": {
    "minecraft:the_nether": {
      "video": "videos/dimensions/nether.mp4",
      "skippable": true,
      "repeatableInSameSession": true,
      "enableBackground": true,
      "colorBackground": "#CC000000",
      "videoTitle": "&cEntered the Nether",
      "videoTitlePosition": "bottomLeft",
      "videoDescription": "&7Bring fire resistance",
      "videoDescriptionPosition": "bottomLeft"
    },
    "minecraft:the_end": {
      "video": "videos/dimensions/end.mp4",
      "skippable": false,
      "repeatableInSameSession": false,
      "enableBackground": true,
      "colorBackground": "#CC000000",
      "videoTitle": "&5The End awaits",
      "videoTitlePosition": "bottomLeft",
      "videoDescription": "&dPrepare for the dragon",
      "videoDescriptionPosition": "bottomLeft"
    }
  },
  "consumedConditionSessions": []
}
```

---

## 🛠️ Support

- Found a bug or want to request a feature?

  [<img alt="Report Issues" src="https://img.shields.io/badge/Report-Issues-red?style=for-the-badge&logo=github&logoColor=white" />](https://github.com/MateoF024/conditionalvideos)