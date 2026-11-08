# Windows development and operations

Run these commands from the repository root. For the cross-platform overview, see the [project README](../README.md).

## Windows development

Requirements: modern x64 Windows, Node.js **22.16+**, Rust stable with the MSVC target, Visual Studio C++ Build Tools and Windows SDK, and Microsoft Edge WebView2 Runtime. The installer bootstraps WebView2 when needed. Network access is needed to download dependencies and media engines.

```powershell
npm install
npm run engines:install
npm run tauri dev
```

`npm run dev` opens a browser UI preview; real downloads require the Tauri desktop app. The preview explicitly indicates that it has no desktop access. `npm run build` compiles the web assets only.

## Production packaging

```powershell
npm run engines:install
npm run build
cargo check --manifest-path src-tauri/Cargo.toml
cargo clippy --manifest-path src-tauri/Cargo.toml -- -D warnings
cargo test --manifest-path src-tauri/Cargo.toml
npm test
npm run lint
npm run tauri build
```

The executable is `src-tauri/target/release/ythub.exe`. NSIS installers are in `src-tauri/target/release/bundle/nsis/`. `scripts/package-portable.ps1` creates a portable folder/ZIP containing the executable and engine resources. A portable build still uses the normal per-user application-data directory for preferences and requires WebView2. Local installers are unsigned unless you configure a signing certificate; Windows may show publisher reputation prompts.

## Engine provisioning

`scripts/provision-engines.ps1` retrieves standalone x64 Windows yt-dlp, FFmpeg, FFprobe, and Deno (YouTube JavaScript challenges) from their upstream GitHub releases. Assets must match GitHub's SHA256 digest. The source URLs/version/digests are recorded in `binaries/manifest.json`. Executables are ignored by Git and bundled as resources during packaging. Python is bundled in the upstream yt-dlp executable. Custom executable paths are supported in Settings. The yt-dlp updater copies the bundled engine into the user's application-data directory before updating, avoiding writes to Program Files.

Read [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) before redistributing the bundled GPL FFmpeg build; corresponding-source obligations apply. Upstream release archives and exact build recipes must accompany your distribution process.

## Structure

```text
src/                 React pages, components, API boundary, store, types, utilities
src-tauri/src/       Rust command boundary, queue/process manager, engine wrapper,
                     archive service, models, persistent storage and validation
src-tauri/binaries/  Provisioned local engine resources (ignored executables)
scripts/             Windows engine provisioning and portable packaging
tests/               URL boundary and frontend queue/progress/settings tests
docs/                Implementation architecture and operational notes
```

Default output: `Downloads/Hertix/{Audio,Video,Playlists,ZIP,Temp}`. Logs, compact playlist manifests and `state.sqlite3` live in `%APPDATA%/dev.ravindran.ythub` (the OS path resolved by Tauri). The previous JSON state migrates automatically and is preserved. Settings include local cookies/proxy paths; these are not encrypted. Cookies content, credentials, complete command lines, and media bytes are never logged. Media bytes are never sent to React; settings remain accessible to the local settings UI.

## Testing and verification

Rust tests cover filenames, write probes, persistence/recovery, metadata parsing, quality and argument validation, progress parsing, and streaming ZIP ordering/cancellation. React tests cover URL validation, progress values, task rendering, and queue empty states. See [verification.md](verification.md) for the actual validation results and external-access limitations from this build.

## Troubleshooting

- **Engine missing**: provision engines or choose custom `.exe` paths, save Settings, then check versions.
- **YouTube unavailable / bot challenge**: update yt-dlp and verify Deno. If you have legitimate access, explicitly supply your own Netscape-format cookies file. Browser cookies are never read automatically.
- **Resume**: retained `.part` files are reused by yt-dlp. Pausing terminates the process tree; conversion restarts safely from available source material.
- **ZIP disk error**: restore free space and write permission in the playlist's recorded ZIP folder, then retry ZIP in History. Folder preferences apply to newly queued playlists; successful media is retained when packaging fails.
- **Folder permission error**: use a writable local folder; all three storage locations are probed before new jobs.
- **State interruption**: unfinished tasks restore as Interrupted. Resume them explicitly. SQLite retains staged media and playlist cursors; legacy JSON files remain preserved after migration.

Only download content you have permission to download and use. No DRM bypass features are included.
