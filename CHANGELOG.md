# Changelog

## 1.2.0

### Added

- **Multiplayer loading screen for every condition.** Whenever a condition (`firstJoin`, `playerDeath`, `deathByEntity`, `entityKilled`, `advancementCompleted`, `dimensionChanged`) is about to play a local-file video that is still being transferred from the server, a full-screen black overlay with an animated spinner and a translatable label takes over until the download finishes, then hands off seamlessly to the playback screen. World sounds are muted while it is up. For `firstJoin` specifically, the server also keeps the player in Spectator while the overlay is on screen, matching the previous behavior; other conditions only display the overlay.
- **`titleTextScale` and `descriptionTextScale`.** Per-entry font multipliers for the title and description overlays.
- **`textBoxOpacity`.** Per-entry control over the background opacity of the title/description box.
- **YouTube and HTTP/HTTPS URL sources.** Any condition entry can now reference a URL (including YouTube links) as its `source`. URL playback is powered by **WATERMeDIA v3** and the optional **WATERMeDIA YouTube Extension**; URLs are excluded from the server↔client file transfer pipeline and resolved independently by each client.
- **`videoVolume`.** Per-entry audio volume control (`0.0`–`1.0`).
- **`videoLoop`.** When `true`, the entry replays itself seamlessly until the player skips it with `ESC`. Forces `skippable = true`; if the user configures `videoLoop = true` together with `skippable = false` the entry now safely plays once and a configuration warning is logged.
- **Playlists per condition.** The single `video` string field has been replaced by `videos: [...]`, a list of `VideoEntry` items played in order. A `playlistLoop` flag at the condition level repeats the whole list once the last entry finishes.
- **Transitions between playlist entries.** Each entry can declare a `transition` (`cut` — default, frame-perfect — or `fadeOut/In`) and an optional `nextAt` cue point (seconds) at which the player should hand off to the next entry instead of waiting for the natural end of the video.
- **Versioned config schema with automatic migration.** Configs from 1.1.x are upgraded the first time 1.2.0 reads them. A backup of the original is written next to the file as `*.bak-v<previousVersion>` before the migration runs.
- **Auto-hiding cursor.** The mouse cursor fades out after a few seconds of inactivity during video playback and reappears the instant the mouse moves.
- **Release-after-hold ESC.** Holding `ESC` for roughly one second and then releasing closes the playback screen; the action only fires on release so the in-world pause menu does not toggle if the key stays pressed.
- **Spanish locales.** Translations shipped for `es_es`, `es_ar`, and `es_mx` in addition to the existing `en_us`.

### Changed

- **Backend upgrade to WATERMeDIA v3.** The video pipeline now targets WATERMeDIA's v3 API natively, which also covers URL streaming, so a separate "YouTube plugin" dependency is no longer required for non-YouTube URLs. The YouTube Extension mod remains optional for YouTube links specifically.
- **Server-side mod surface trimmed.** A dedicated Forge/Fabric server no longer references any client-only or WATERMeDIA classes, eliminating crash paths reported on dedicated servers.

### Fixed

- **F11 / window resize no longer re-runs the playback intro.** Pressing F11 (fullscreen toggle) or resizing the window during a video used to reset the elapsed-time counter, which re-played the title / description / skip-hint fade-in animation and re-positioned the text box. Resizes are now non-destructive: the backend stays alive and the overlay timers keep their progress.
