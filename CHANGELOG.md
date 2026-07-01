# Changelog

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
