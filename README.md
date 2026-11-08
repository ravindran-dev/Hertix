<div align="center">
  <img src="public/hertix-symbol.png" alt="Hertix" width="96" />
  <h1>Hertix</h1>
  <p><strong>Music · discovery · playback</strong></p>
  <p>A local-first music player and media library for Windows and Android.</p>
</div>

Hertix discovers music, manages authorized media downloads and conversion, and keeps local files on your device. Windows uses React, TypeScript, Tauri and Rust. Android is a separate native Kotlin/Jetpack Compose application with Media3 playback. An optional Hertix account enables cloud library sync; local playback and downloads work without it.

## Platforms

| Platform | Application | Build requirements | Distribution |
| --- | --- | --- | --- |
| Android 8+ | Kotlin, Jetpack Compose, Media3 | JDK 17, Android SDK 36 | Development APK; production signing required |
| Windows x64 | React 19, TypeScript, Tauri 2, Rust | Node.js 22.16+, Rust/MSVC, C++ Build Tools, Windows SDK, WebView2 | NSIS installer or portable package |
| iOS | No target in this repository | — | — |

## Features

### Download and organize

- Analyze individual videos and playlists using yt-dlp metadata.
- Save MP3 audio or video with quality selection, conversion and optional metadata/artwork.
- Track real download progress, processing, export, completion and failure states.
- Pause, resume, cancel and retry downloads; inspect history and errors.
- Package playlist outputs as ZIP archives and choose local output locations.
- Keep preferences and task history locally, with recovery of interrupted work.

Queue policies, storage mechanisms and platform integration differ between Android and Windows; see the [architecture](docs/architecture.md).

### Native Android music

- Play completed audio downloads or browse existing phone music through Android's audio index.
- Search and organize tracks, playlists, favorites and recent listening history.
- Use a persistent mini-player, full player, seek and media-volume sliders, queue, shuffle/repeat, playback speed, local lyrics and sleep timer.
- Continue playback through the service-owned Media3 engine while navigating the app.
- Access capability-aware audio effects; unsupported features are explained or disabled.
- Navigate through Home, Downloads, Library and Settings with animated glass surfaces, native blur on Android 12+, adaptive text and system-safe spacing.

See [player behavior and persistence](docs/MUSIC_PLAYER.md) and the [device audit](docs/MUSIC_PLAYER_AUDIT.md) for tested behavior and platform limitations.

## Get started

Clone the repository:

```sh
git clone https://github.com/ravindran-dev/YTHub.git
cd YTHub
```

### Android

Install JDK 17 and Android SDK platform 36. Set `ANDROID_HOME` or configure the ignored `android/local.properties` file. The first build needs network access for dependencies.

```sh
./android/gradlew -p android :app:assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `android\gradlew.bat -p android :app:assembleDebug`. Debug builds use your local Android development key; preserve it to install updates without uninstalling. An APK signed with another key cannot replace the existing installation directly.

Open **Library → Phone music** to grant audio access and select existing songs. A playlist's **+** adds phone music to that playlist. Source files stay in place.

Full instructions: [Android development](android/README.md).

### Windows

Run in PowerShell on a configured Windows development machine:

```powershell
npm ci
npm run engines:install
npm run tauri dev
```

`npm run dev` starts a browser preview. Native downloads require the Tauri application. Engine provisioning downloads and verifies the Windows yt-dlp, FFmpeg, FFprobe and Deno resources.

Full instructions: [Windows development](docs/windows-development.md), [engine provisioning](docs/download-engine.md), [packaging](docs/packaging.md).

## Architecture

The applications share product identity and media workflows, while each platform owns its native runtime and persistence.

| Layer | Android | Windows |
| --- | --- | --- |
| Interface | Compose screens, adaptive layouts, motion/glass components | React pages, typed API boundary, Zustand |
| Downloads | `DownloadStore` and foreground `DownloadService` | Rust manager, bounded download/processing/archive pools |
| Playback | One service-owned ExoPlayer and MediaSession | Android player is not a desktop feature |
| Durable state | Atomic music JSON, download JSON, SharedPreferences | SQLite queue/history and compact playlist manifests |
| Files | MediaStore, SAF grants, private imports, FileProvider | Validated local paths and native filesystem operations |
| Media tools | youtubedl-android / FFmpeg integration | Provisioned standalone Windows executables |

Read the [architecture guide](docs/architecture.md) for component boundaries, data flows, recovery and extension rules.

```text
android/                 Native Android app, services, tests and licensing
src/                     React interface, API boundary, store and utilities
src-tauri/               Rust desktop runtime and media pipeline
scripts/                 Windows engine provisioning and packaging
tests/                   Frontend regression tests
docs/                    Architecture, operations and verification reports
.github/workflows/       Android and Windows build validation
```

## Development checks

Frontend:

```sh
npm ci
npm run lint
npm test
npm run build
```

Android:

```sh
./android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
# Requires an authorized Android device or emulator:
./android/gradlew -p android :app:connectedDebugAndroidTest
```

Desktop native checks run on Windows:

```powershell
cargo fmt --manifest-path src-tauri/Cargo.toml -- --check
cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path src-tauri/Cargo.toml
npm run tauri build
```

Instrumentation includes generated local audio and metadata fixtures; it does not download thousands of online videos. Some physical/background/network tests are opt-in and are not verified by a default test run. CI build success does not establish physical-device acceptance.

## Verification and limitations

- [Android integration audit](docs/MUSIC_PLAYER_AUDIT.md): playback, persistence, offline media, download integration and background-service checks.
- [Mobile UI and motion audit](docs/UI_COMPACT_AUDIT.md): device tests, responsive layouts, glass rendering, frame samples and remaining performance limits.
- [Windows verification](docs/verification.md): dated scheduler, media, archive and packaging results.

Android lock-screen controls, Bluetooth/headset behavior, real interruptions and OEM background restrictions still require broader physical-device acceptance. Crossfade, virtualizer and loudness normalization are not implemented. Audio-effect availability varies by device. Frame samples include occasional slow frames; sustained 60fps is not certified.

YouTube availability and authentication requirements can change; an engine update may be necessary. No iOS application is included. Production Android signing and Windows code signing require publisher-controlled credentials.

## Documentation

| Topic | Guide |
| --- | --- |
| System design | [Architecture](docs/architecture.md) |
| Android build and permissions | [Android README](android/README.md) |
| Android music integration | [Music player](docs/MUSIC_PLAYER.md) |
| Download pipeline | [Download engine](docs/download-engine.md) |
| Scheduling and recovery | [Concurrency](docs/concurrency.md), [reliability](docs/reliability.md), [recovery](docs/recovery.md) |
| Distribution | [Packaging](docs/packaging.md), [Windows development](docs/windows-development.md) |
| Troubleshooting | [Troubleshooting](docs/troubleshooting.md) |

## Contributing

Keep native media work out of presentation components. Preserve track identity, playlist ordering, source files and durable state when changing UI or import flows. Include appropriate regression checks, explain platform limitations, and distinguish device verification from emulator or fixture tests. Never commit signing keys, cookies, credentials, downloaded media or local build artifacts.

## Maintainer

[Ravindran — @ravindran-dev](https://github.com/ravindran-dev) · [ravindrans.dev@gmail.com](mailto:ravindrans.dev@gmail.com)

## License

The Windows application and root project are [MIT licensed](LICENSE). The Android application is [GPL-3.0 licensed](android/LICENSE) because of its linked media dependencies. Preserve the [root third-party notices](THIRD_PARTY_NOTICES.md), [Android notices](android/THIRD_PARTY_NOTICES.md) and [Inter font license](android/licenses/Inter-OFL.txt) when distributing builds. The Android package must not be described as MIT-only.

Use downloaded content only with the rights or permissions required for your use.
