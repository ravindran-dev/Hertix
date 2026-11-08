# Hertix for Android

A native Kotlin / Jetpack Compose app with the Hertix gradient H/play logo, dark and light themes, a local media library and player. This project is separate from the Windows Tauri app.

## Build

Run from the repository root on macOS/Linux:

```sh
./android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The PowerShell example below runs inside `android/`.

Requirements: JDK 17 or 21, Android SDK platform 36, and an Internet connection for the first Gradle dependency download. Set `ANDROID_HOME` or create an ignored `local.properties` containing `sdk.dir=C\:/path/to/Android/Sdk`.

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The installable development APK is `app/build/outputs/apk/debug/app-debug.apk`. It is signed with the local Android development key. Keep that key to install subsequent updates without uninstalling the app. The package ID remains `dev.ravindran.ythub.mobile` so existing YTHub installations receive Hertix updates in place and retain app data and account sessions. A store release needs a dedicated private release key and release validation.

```powershell
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_DEVICE_SERIAL shell am start -n dev.ravindran.ythub.mobile/.MainActivity
```

Minimum Android 8; ARM64 phones and x86-64 emulators are included. The app requests no broad storage access. New Android 10+ exports use `Downloads/Hertix`; existing files in `Downloads/YTHub` remain in place and stay readable. A selected folder uses a persistent Storage Access Framework grant. Android 8–9 defaults to app-scoped storage. Share a YouTube link from another app to Hertix or paste it on Home.

## Implemented flows

- Onboarding checks the bundled Python, yt-dlp and FFmpeg initialization before enabling Get started.
- Home accepts HTTPS YouTube links, analyzes single media or playlists, and lets you choose MP3/MP4, bitrate/quality, ordering and ZIP export. MP3 defaults to 320 kbps.
- Media details show actual metadata and thumbnails. Playlists show available entries, capped at 500 per analysis; the UI explicitly displays the limit.
- A persistent FIFO queue runs 1–3 playlist/single jobs at once (default 2), with foreground progress notification, pause/cancel/resume, retry, network retries and partial-file continuation. Each playlist processes items sequentially.
- Completed audio plays inside YTHub through Media3, with a local library, playlists, queue, favorites, embedded/imported lyrics and automatic LRCLIB lookup for synchronized or plain lyrics, sleep timer and session-scoped audio effects. Videos open with an installed video player. See [music player implementation and limits](../docs/MUSIC_PLAYER.md).
- Folders can be opened through a file manager, and URLs copied. ZIP export streams local files; individual media retention is configurable.
- History includes search, Audio/Video/Playlist filters, details, retry and removal. Removing a history row leaves exported files intact.
- Settings include dark/light/system appearance, default quality, concurrency, auto start, folder selection, ZIP options, notifications, retries, imported Netscape cookies, proxy, engine updates and per-download error logs.
- Storage shows device free space, totals recorded in history, archive counts and temporary-file cleanup. Partial files for paused/interrupted tasks are retained.

## Recovery and practical limits

Queue records use a synced temporary JSON file renamed into place, with writes serialized. Active/queued tasks become Interrupted on process restart and require explicit Resume. Output paths are stored as Android content URIs; partial public exports are deleted on write failure. A task saves exported filenames to avoid exporting the same files again on retry. Progress is supplied by the engine; size is finalized after download. The latest yt-dlp progress line carries live transfer size/speed information.

Android may stop long-running background work or enforce the Android 15+ data-sync foreground-service time budget. The service pauses jobs on timeout. Resume from the app. OEM battery restrictions and network behavior require testing on the target phone. Downloads are not scheduled automatically after reboot. yt-dlp pause stops the native request and continues partial downloads on resume; conversion may restart rather than resume. This initial Android engine uses the upstream process wrapper; inherited FFmpeg child termination needs further stress verification.

Playlist duration/view metadata are shown only when available. Failed playlist retries attempt all entries and reuse local completed media. ZIPs with failed items are partial archives; the task remains Failed and records item counts. The archive filename is based on the playlist title. No iOS binary is included.

## Licensing

The Android application is GPL-3.0 because it links the GPL-3.0 youtubedl-android / FFmpeg wrapper. See `LICENSE` and `THIRD_PARTY_NOTICES.md`. The root Windows application's MIT license is unchanged. Do not describe the Android package as MIT-only.

Architecture and ownership boundaries: [system architecture](../docs/architecture.md).

Final music-player integration, performance and device results: [audit report](../docs/MUSIC_PLAYER_AUDIT.md).

Phone music: Library → **Phone music**, or a playlist’s **+**, browses Android’s audio library with search, selection and live refresh. Grant Music and audio access when prompted. Source files stay in place.

## Interface and verification

The main destinations are Home, Downloads, Library and Settings. Playback opens from a track or the persistent mini-player. Compose state is preserved between destinations; the Media3 service owns playback independently of the UI. The glass navigation and mini-player blur real content on Android 12+, with measured scroll padding and system-safe keyboard/player layouts.

Build the installed test runner with `:app:assembleDebugAndroidTest`. Use a dedicated emulator for the full `:app:connectedDebugAndroidTest` suite; some background/offline/restart/network tests are opt-in. Physical-device verification should select the intended test classes and record OS, device, permissions and any untested hardware routes separately. Test fixtures are not bundled production media.

See the [UI and motion audit](../docs/UI_COMPACT_AUDIT.md) for responsive widths, enlarged text, glass, transport behavior, frame samples and known limitations. Android CI builds APKs, runs JVM tests and lint, and publishes development artifacts; it does not establish physical lock-screen or Bluetooth acceptance.
