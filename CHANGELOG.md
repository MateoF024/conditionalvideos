# Changelog

> **BREAKING CHANGES** are changes that break an existing instance compared to the previous version: after updating, something stops working as it did until you adapt it.

## 1.6.0

### Added
- **Waiting room for `firstJoin`.** Players wait behind a color, image or GIF, then watch the video together. Choose how many players, which names or teams are needed, and whether it starts by itself or when an operator says so.
- **`worldPosition` condition.** Plays a video when a player enters or leaves an area.
- **LAN support.** Guests now receive the host's conditions, options and video files, as on a dedicated server.
- **Player protection (`protectPlayer`, on by default).** During any video the player takes no damage or hunger, picks up nothing and can't be pushed. Their game mode doesn't change.
- **World freeze (`freezeWorld`, on by default for `firstJoin` only).** The world stops while every connected player is watching.
- **Exclusions.** `excludePlayers`, `excludeTeams` and `excludeOps` per condition.
- **More playback options.** `conditionDelay`, `replayOnReconnect` and `disableLoadingScreen` per condition, and `startAt` per video.
- **`defaultVolume`** in `conditionalvideos-common.json`, for videos without their own volume.
- **More text placement.** Titles and descriptions accept `center` or exact `X/Y` pixels, and can be aligned with `videoTitleAlignment` and `videoDescriptionAlignment`.
- **New commands.** `reset` clears what a player has watched, `set` edits a value and applies it right away, and `waitingroom` controls the room.
- **`play` options.** `delay`, `exclude`, `excludeteam`, `excludeops`, `once` and `sync` change a single run without touching the file.
- **API:** `playOnServer` and `PlayOptions`.
- **Config problems are reported** in the log and in the chat of whoever can fix them.
- **Condition files edited by hand apply right away**, also for connected players.

### Changed
- **BREAKING: WATERMeDIA `3.0.0` and Native Binaries `3.0.0` are now required.** Older versions, including the `3.0.0.x` betas, are no longer compatible.
  - API `3.0.0.23` → `3.0.0`
  - Native Binaries `3.0.0.6` → `3.0.0`
- **BREAKING: each condition now has its own file.** `conditionalvideos.json` and `conditionalvideos-server.json` are replaced by the `conditions/` and `server-conditions/` folders in `config/conditionalvideos/`. They migrate on the first launch and the originals stay as `.bak`, but modpacks or scripts that ship or edit the old files must move to the new ones.
- **BREAKING: commands name a condition with separate arguments.** `play @a entityKilled/minecraft:warden` is now `play @a entityKilled minecraft:warden`. Command blocks and functions need updating. API keys don't change.
- **BREAKING: `repeatableInSameSession: false` now means once per player and world, also on servers,** where it used to play again on every connection. Singleplayer worlds keep what they already watched.
- **BREAKING: `play` skips players who still have a video going** instead of queuing the new one for them.
- **BREAKING: titles and descriptions no longer wrap on their own.** They only break at `\n`, so long single-line texts need it added. Text boxes now fit their text.
- **`repeatableInSameSession` left out of a file now means `true`,** except on `firstJoin`. Migrated conditions keep playing as they did.
- **`firstJoin` no longer puts the player in spectator mode.** Player protection replaces it.
- **Videos now silence every sound category except Master,** so mods with their own audio that follow the sliders go quiet too. `allowGameSounds` still turns this off.
- **`videoQuality` uses WATERMeDIA's quality names:** `LOWEST`, `LOWER`, `LOW`, `LOWISH`, `MEDIUM`, `HIGH`, `HIGHER`, `HIGHEST` and `ULTRA_8K`.
- **The server also sets `videoLoadTimeoutSeconds` and `defaultVolume`** for its players.
- **Clearer `debugLogging`.** Client and server each read their own option, every line says where it runs, and the log explains why a video plays or not.
- **New license.** All Rights Reserved is replaced by the ConditionalVideos License: free for any server and any free modpack, while charging for anything that uses the mod requires permission.
- Inherited the fixes and hardening of the updated WATERMeDIA stack.

### Fixed
- **Clients can no longer put themselves in spectator mode on a server.**
- **A disconnect or a crash no longer leaves a player stuck in spectator,** and creative players no longer come back flying.
- **Vulkan stalls and crashes while a video plays on 26.2.**
- **`videoLoop` and playlists no longer wait up to 10 seconds between videos.** The next video now starts as soon as the current one ends.
- **YouTube videos no longer lose part of their picture after a long pause.** A video paused for a while, including one waiting its turn in a loop or playlist, now resumes without skipping anything.
- **The world no longer shows between videos** during skips and transitions.
- **The mod no longer rewrites your config files.** `&` codes stay as written, and the common config only gets the options it lacks.

## 1.5.1

### Fixed
- **Playback no longer breaks on WATERMeDIA `3.0.0.23`.** That release changed part of its API, which left 1.5.0 unable to start a video once WATERMeDIA was updated.

### Changed
- **Updated WATERMeDIA stack:**
  - API `3.0.0.22` → `3.0.0.23`
  - Native Binaries `3.0.0.5` → `3.0.0.6`
- **WATERMeDIA `3.0.0.23`+ and Native Binaries `3.0.0.6`+ are now required.** Older versions are no longer compatible.
- Inherited the fixes and hardening of the updated WATERMeDIA stack.

## 1.5.0

### Added
- **Minecraft 26.1.2 and 26.2 support**
- **Vulkan renderer support on 26.2.** Videos play on either renderer; Vulkan support is EXPERIMENTAL.
- **Clearer diagnostics**: `debugLogging` now also reports the environment (OS, Java, Minecraft, graphics backend and driver, WATERMeDIA state) and a per-second playback line.
- **Explicit message when WATERMeDIA's FFmpeg backend fails to load**, instead of a video that silently never starts.

### Changed
- **Updated WATERMeDIA stack:**
  - API `3.0.0.21` → `3.0.0.22`
  - Native Binaries `rc.4` → `3.0.0.5`
- **Videos now upload at their native resolution.** The automatic upload-resolution cap added in 1.4.0 was removed: the frame is no longer downscaled before upload, so playback keeps the quality of the source and the GPU handles the scaling.
- **Mature-content filtering is now handled entirely by WATERMeDIA.** The `blockMatureContent` option is unchanged and mirrors WATERMeDIA's own policy, which covers more sources than the previous built-in domain list.
- Inherited the fixes and hardening of the updated WATERMeDIA stack.

### Fixed
- **Pausing a video before it started playing no longer gets stuck on the loading screen.**

## 1.4.0

### Added
- **Minecraft 1.21.11 support**
- **High-quality YouTube** playback, now **built into WATERMeDIA natively** through `yt-dlp` — no separate extension mod is required anymore.
- **More platform sources out of the box** via the same native `yt-dlp` integration: **Facebook, Instagram, Newgrounds, SoundCloud**.
- **Automatic upload-resolution cap**: video frames are never uploaded larger than the game window, so a 4K video on a 1080p display no longer wastes VRAM or bandwidth.
- **`debugLogging`** option in `conditionalvideos-common.json` for detailed diagnostics (client-only, default off).
- **Hold-to-skip-the-playlist gesture with a progress bar.** Tap the skip key to skip the current video, or hold it to fill a bar and skip the whole playlist; a single-video tap shows no bar. A "release to skip the playlist" message appears once the bar is full.

### Changed
- **Updated WATERMeDIA stack:**
  - API `3.0.0.17` → `3.0.0.21`
  - Native Binaries `rc.1` → `rc.4` (FFmpeg 8.0.1; now also provisions the bundled `yt-dlp` / botguard binaries)
  - The old optional **Platform Extension** is gone — YouTube and the extra platforms were merged into WATERMeDIA itself, so only the **Multimedia API** and **Native Binaries** are needed.
- Inherited several WATERMeDIA playback improvements: AV1 software decode when the GPU can't, AMD (AMF) hardware decoding, tighter A/V sync, correct audio after pause/underrun, and fewer torn frames under load.

### Fixed
- **The whole-playlist skip now happens on key release.** Releasing the skip key after the hold completes skips the playlist; releasing early no longer skips anything and the current video keeps playing.
- **The skip hint is no longer shown for `skippable = false` videos.**
- **Local videos now play on their first attempt after each launch.**
- Hardened player startup with a dedicated retry-grace counter for player-creation failures, instead of relying solely on the screen-level timeout.
- **Unresolvable URLs (e.g. a deleted/unavailable YouTube video) no longer storm the extractor.** A failing source was retried about once a second for 30 seconds; with the yt-dlp extractor each retry spawns a process, which starved the CPU and could freeze a currently-playing video (image stuck, audio continuing) and hang the next entry on a long loading screen. Failed sources are now retried a few times with backoff, then logged as invalid/unavailable and skipped to the next entry (or playback closes if it was the only/last one). A next entry that already failed while preloading is skipped at once instead of waiting.

## 1.3.0 — New Conditions

- Added new condition types (item obtained, item crafted, recipe unlocked, scoreboard thresholds, totem used, bed sleep, and mod-defined custom conditions).
- Added per-condition playlists, the one-at-a-time playback queue, and the public API in `org.mateof24.conditionalvideos.api`.
- Config schema migrated `v2` → `v3` (additive: new condition maps created empty, existing entries preserved).

## 1.2.0 — WATERMeDIA V3 Integration

- Migrated the video backend to WATERMeDIA v3.

## 1.1.x

- Legacy single-video-per-condition configuration. Configs from this era are migrated automatically into single-entry `videos: [...]` playlists, with a backup written next to the original file.
