# Navigation and player follow-up — 2026-11-05

## Rendering

- Home/Downloads/Library/Settings tab changes are immediate; the player uses a short vertical reveal and detail pages a short fade. The library no longer adds a second delayed section reveal.
- Non-dock glass controls reuse the shared half-resolution frosted scene, keeping text/icons sharp. Dock effects render at quarter resolution with no chromatic dispersion; lens refraction is suspended while the library scrolls.
- Foreground content is recorded once and reused for the viewport and cropped dock capture. Library scrolling limits capture refresh to 30 Hz; content and input continue at the normal frame rate. Capture stops when the full player or keyboard hides the dock.
- Playback-position ticks update the seek controls independently of the full player. Timestamped lyrics still observe live position, and skip actions read the current position at click time.

## Player

- A visible square album cover replaces the empty artwork slot. Existing same-album artwork can fill a missing track cover. Tracks without artwork keep the neutral placeholder; no unrelated artwork is downloaded or invented.
- The BitChord-inspired layout has title/artist, favorite, seek, shuffle/previous/play/next/repeat, volume and Lyrics/Output/Queue. Timer and skip actions remain in track options. Colors follow light/dark themes.
- The mini-player uses a 36 dp rounded cover, 12 dp leading inset and explicit clipping, inside the existing capsule. Previous/play/next remain available.

## Validation

- 39 JVM tests, debug and preview assembly, instrumentation assembly, Android lint and diff whitespace checks passed.
- Six physical-phone UI checks passed: real-input scrolling/navigation preserves playback state; both player themes show the cover and controls; mini-player artwork bounds remain inside the bar; backup cancellation retains consent; Bgm remains persisted with 46 songs; playlist trailing padding and search/navigation geometry remain correct.
- Player screenshots checked in both themes on the OnePlus CPH2603. The final installed build is the non-debug preview, signed with the existing installation key, using an in-place update.
- Real Android touch input: five up/down pairs of 300 ms swipes through the 148-song library, with `dumpsys gfxinfo` reset immediately before capture. Current debug: 218 frames, 14 deadline misses (6.42%), median 21 ms, p95 40 ms. Installed preview: 226 frames, 4 deadline misses (1.77%), median 16 ms, p95 21 ms, p99 26 ms. These compare current build modes on one device; they are not an old-versus-new speedup or a guarantee for every screen/device.
- The original Compose synthetic-swipe timing sample was discarded for performance conclusions: its event clock batches frames and does not represent real finger input. The device test now injects wall-clock MotionEvents.

Offline storage, playlists and backup permissions are unchanged by this follow-up. No cloud upload was enabled.
