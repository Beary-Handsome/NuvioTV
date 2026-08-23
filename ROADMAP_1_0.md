# Nuvio Android TV 1.0 Roadmap

This document defines the remaining work required for a dependable, polished
Android TV 1.0 release. Reliability of the core viewing loop is the release
gate; optional features do not need to be complete before 1.0.

## Release Blockers

### Playback progress

- [x] Save local progress shortly after playback starts.
- [x] Save local progress periodically without excessive remote API traffic.
- [x] Save MPV progress on manual pause and application backgrounding.
- [x] Refresh Continue Watching from optimistic updates and when Home resumes.
- [x] Prevent an older asynchronous save from overwriting a newer position.
- [ ] Verify progress behavior with both ExoPlayer and MPV on a physical TV.
- [ ] Verify pause, exit, backgrounding, process recreation, crash recovery,
      seeking, episode switching, autoplay, replay, and natural completion.
- [ ] Ensure a remote tracking refresh cannot replace newer local progress.

### Playback and source reliability

- [ ] Test EasyNews, Real-Debrid, TorBox, direct HTTP, cloud files, individual
      torrents, and season packs on a physical Android TV device.
- [ ] Verify expired-link re-resolution for HTTP 401, 403, 404, and 410.
- [ ] Verify automatic source failover during startup, buffering, decoder
      errors, and mid-playback URL expiration.
- [ ] Confirm failed sources advance through every viable candidate without
      looping or prematurely stopping.
- [ ] Run several complete movies and episodes, not only startup checks.

### Scraping acceptance

- [ ] Maintain a regression set of difficult movies and episodes, including
      *Go! Go! Cory Carson* episodes and season packs.
- [ ] Confirm Riven returns generic torrent candidates in addition to
      server-confirmed provider bindings.
- [ ] Confirm all configured providers are merged independently.
- [ ] Test provider arrival-order races and ensure results never disappear.
- [ ] Detect and report provider outages, irrelevant results, timeouts,
      matching rejections, and season-pack file-selection failures.

### Trakt and tracking

- [ ] Verify watched history imports completely and watched badges agree with
      Trakt.
- [ ] Verify Continue Watching, scrobble start/pause/stop, completion, next up,
      seeking, replay, removal, and episode switching.
- [ ] Define and test conflict resolution between newer local state and remote
      Trakt state.
- [x] Retain locally saved in-session progress in the SIMKL projection.

### Release engineering

- [x] Increment the version code and version name for the 1.0 release candidate.
- [x] Protect and back up the release signing key without exposing credentials.
- [x] Produce a clearly named signed universal Android TV APK.
- [ ] Verify upgrades preserve addons, credentials, tracking state, profiles,
      library data, and user preferences.
- [ ] Verify clean installation and upgrade installation on physical hardware.
- [x] Write concise RC release notes and retain the previous published APK.
- [x] Confirm the About screen reports the release-candidate version.

### Security, privacy, and crash readiness

- [ ] Eliminate all known reproducible crashes in primary workflows.
- [x] Confirm logs, reports, and diagnostics redact debrid, EasyNews, addon,
      Nuvio, and Trakt credentials.
- [ ] Ensure release builds contain no debug endpoints or excessive logging.
- [ ] Test offline startup, Riven downtime, provider downtime, and recovery.

## Product Polish

### Loading and source-list stability

- [x] Keep existing sources visible while a refresh is running.
- [ ] Publish stable, throttled source snapshots without list flashing.
- [ ] Distinguish searching, no matches, provider unavailable, timed out, and
      completed states.
- [ ] Show unobtrusive per-provider progress and diagnostics.
- [x] Keep source ordering stable as providers finish in different orders.

### Source presentation

- [ ] Present provider, cache/cloud status, resolution, codec, HDR, audio,
      language, and file size consistently.
- [ ] Prioritize exact cloud matches, confirmed cached results, locally
      verified candidates, and unresolved torrents in that order.
- [ ] Remember preferred quality and provider without hiding alternatives.
- [ ] Make the selected and currently attempted source unmistakable.

### Playback transitions and recovery

- [ ] Preserve artwork, title, season, and episode information while preparing
      a link.
- [ ] Show which source is being attempted during automatic failover.
- [ ] Briefly explain when a failed source is skipped.
- [ ] Avoid unnecessary black-screen gaps between sources and episodes.
- [ ] Use concise recovery actions such as Try another source, Refresh sources,
      Retry, and Report issue.
- [ ] Keep technical exceptions in diagnostics instead of user-facing dialogs.
- [ ] Avoid blocking dialogs for recoverable failures.

### Continue Watching

- [ ] Display episode title and season/episode numbering consistently.
- [ ] Preserve focus on the item most recently watched after returning Home.
- [ ] Prevent completed episodes from briefly reappearing with stale progress.
- [ ] Make Remove, Restart, Play manually, and Play next actions reliable.
- [ ] Confirm progress bars update immediately after returning from Player.

### Remote navigation and focus

- [ ] Give every screen and dialog a predictable initial focus target.
- [ ] Make Back close the topmost overlay before exiting playback.
- [ ] Restore focus and scroll position after returning from Details or Player.
- [ ] Prevent focus jumps when catalogs, recommendations, or source lists
      update.
- [ ] Verify all primary workflows using only a directional remote.

### Visual consistency and accessibility

- [ ] Standardize spacing, typography, badges, dialogs, menus, icon buttons,
      and focus rings.
- [ ] Remove clipped titles, unstable card dimensions, and inconsistent poster
      aspect ratios.
- [ ] Keep layouts stable while artwork and watched badges load.
- [ ] Use consistent icons and tooltips throughout Settings.
- [ ] Improve skeleton and empty states while keeping them restrained.
- [ ] Test television safe areas and overscan behavior.
- [ ] Review contrast, focus visibility, subtitle readability, and larger UI
      scaling.

### Settings and setup

- [ ] Organize Settings into Playback, Sources, Tracking, Library, Appearance,
      Diagnostics, and About.
- [ ] Hide controls that do not apply to the enabled provider or player engine.
- [ ] Explain destructive actions at confirmation time.
- [ ] Add per-section reset actions in addition to full application reset.
- [ ] Create a focused first-run setup for Trakt, Riven, debrid providers, and
      playback preferences.
- [x] Add provider and network health diagnostics.
- [ ] Add settings and addon configuration import/export.

### Branding and distribution

- [ ] Finalize launcher icon, Android TV banner, screenshots, and release name.
- [ ] Ensure the product name is prominent and consistent throughout the app.
- [ ] Remove development-only wording from visible release UI.

## Automated Validation

- [x] Add tests for the first local-save cadence used by Continue Watching.
- [ ] Add Home-to-Player-to-Home lifecycle tests.
- [ ] Add ExoPlayer and MPV pause/background persistence tests.
- [ ] Add process-death and playback-restoration tests.
- [x] Add monotonic progress-write ordering tests.
- [ ] Add tests for provider refreshes arriving after newer local progress.
- [x] Add source arrival-order and snapshot-stability tests.
- [ ] Add end-to-end tests for Details, Sources, Player, episode switching,
      autoplay, and failover.
- [x] Run the complete unit suite and release build for RC1.

## Riven Operational Cleanup

- [x] Remove Jellyfin/FUSE-era services after confirming Nuvio has no runtime
      dependency on zurg, rclone, rclone-http, or riven-mount.
- [ ] Remove obsolete binder, backfill, blacklist, zombie, and retry workers
      only when equivalent V2 behavior is verified.
- [ ] Consolidate duplicate scraper paths that return the same hashes.
- [ ] Compress and archive old compose files and operational backups before
      removing them from active deployment directories.
- [ ] Retain provider health diagnostics and a documented rollback procedure.

## Deferred: Nuvio Account Removal

Nuvio Account removal is intentionally deferred until after 1.0 stabilization.
Authentication currently touches progress storage, profiles, synchronization,
settings, navigation, dependency injection, and data migrations.

For 1.0:

- [ ] Hide Nuvio Account entry points behind a build flag or disabled setting
      for the personal build.
- [ ] Ensure local profiles and Trakt work without a Nuvio account.
- [ ] Ensure startup and normal navigation never wait for Nuvio authentication.
- [ ] Confirm disabling account UI does not remove local data.

After 1.0:

- [ ] Inventory all Nuvio Account dependencies and stored data.
- [ ] Design migrations that preserve local progress, profiles, and settings.
- [ ] Remove account UI, authentication, synchronization, and backend code in
      staged changes with upgrade tests.
- [ ] Remove obsolete database fields and preferences only after migration has
      shipped and been validated.

## 1.0 Release Gate

The release candidate is ready when it can be installed cleanly and as an
upgrade on a physical Android TV, connected to the intended tracking and stream
providers, and used as the primary player for several days without losing
progress, sources, credentials, focus state, or playback continuity.
