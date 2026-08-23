# Nuvio Android TV 1.0.0-rc1

## Playback and progress

- Continue Watching is established five seconds after playback starts and is
  refreshed locally every 20 seconds without increasing Trakt API traffic.
- MPV now saves progress on manual pause and application backgrounding.
- Progress writes are serialized so delayed snapshots cannot overwrite newer
  playback positions.
- Home refreshes Continue Watching from optimistic progress updates and whenever
  it resumes.
- SIMKL projections retain locally saved in-session progress.

## Sources

- Refreshing Sources keeps the current results visible and usable.
- Results from later providers are merged without replacing earlier providers
  or moving existing rows.
- Failed refreshes retain usable cached sources.
- Source errors use concise user-facing text while technical details remain in
  diagnostics.
- The active source indicator is localized.

## Privacy and operations

- Playback issue reports now redact signed URLs and credentials from raw event
  lines as well as structured fields.
- The unused riven-mount systemd unit was retired after verifying that no zurg,
  rclone, FUSE mount, or active service depends on it.
- Riven V2 remains an on-demand service with no prebuilt media inventory.

## Verification

- Full Android app unit suite passed.
- All 21 Riven V2 tests passed.
- Signed universal release APK built successfully.
- Published and built APK SHA-256:
  `bd7116a09b59ed7e8a254dfd6500dc7d35150fe085857ea67dcaca663fa398b6`

## External release gates

- Install and upgrade verification on the target Android TV.
- Real playback checks across EasyNews, Real-Debrid, and TorBox.
- Multi-day primary-player validation before promoting RC1 to final 1.0.0.
