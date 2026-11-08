# Windows packaging

The supported target is x86_64-pc-windows-msvc. Install C++ Build Tools, a Windows SDK, Rust stable, Node 22.16+, and WebView2 for development.

Provision engines before building. `binaries/` is a Tauri resource directory, not a shell-plugin sidecar. Executable lookup prioritizes configured paths, a writable updated yt-dlp in application data, packaged resources, and (debug only) the source-tree binary directory. Release builds never depend on a developer's absolute path.

`npm run tauri build` produces an NSIS installer and the release executable. `scripts/package-portable.ps1` copies the executable plus the engine resource directory into `artifacts/Hertix-portable` and creates a ZIP. It does not include Rust build caches or node dependencies. Both builds store preferences in application data, not next to the executable. The Tauri NSIS installer can install WebView2 through its default bootstrap behavior.

Codesigning requires a publisher-controlled certificate and is not configured in this repository. Verify installed binaries on a clean Windows machine before public distribution. The local packaging step is separate from public release creation; this task does not publish a GitHub release or push repository changes automatically.

Preserve upstream engine licenses and exact digest/version manifests. For GPL FFmpeg redistribution, maintain corresponding source and build recipes for the exact shipped binary. See THIRD_PARTY_NOTICES.md.
