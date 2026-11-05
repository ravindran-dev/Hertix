# Third-party software

YTHub's original source is MIT licensed. External executables are separate programs invoked through a process boundary, not linked into the Rust application.

| Component | License / source |
|---|---|
| yt-dlp | [Unlicense and dependency notices](https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE); [source and exact release tags](https://github.com/yt-dlp/yt-dlp/releases) |
| FFmpeg / FFprobe | This distribution uses the GPL Windows builds from [yt-dlp/FFmpeg-Builds](https://github.com/yt-dlp/FFmpeg-Builds). [Corresponding build recipes and source references](https://github.com/yt-dlp/FFmpeg-Builds/tree/master); [FFmpeg source](https://git.ffmpeg.org/ffmpeg.git); [license information](https://ffmpeg.org/legal.html). The build archive's license files are copied into installed resources. |
| Deno | [MIT](https://github.com/denoland/deno/blob/main/LICENSE.md), [source and release tags](https://github.com/denoland/deno/releases) |
| Tauri | MIT / Apache-2.0, https://github.com/tauri-apps/tauri |
| React | MIT, https://github.com/facebook/react |
| Lucide | ISC, https://github.com/lucide-icons/lucide |

`src-tauri/binaries/manifest.json` records the downloaded engine release, asset URL, and SHA256 supplied by GitHub. Preserve that manifest and all license files when distributing binaries. Distributors must comply with the licenses of the exact FFmpeg build and its libraries, including providing corresponding source as required; upstream links alone are not a substitute for distributor obligations. This repository does not upload a public release automatically.

## Android glass rendering

The `android/app/src/main/java/dev/ravindran/ythub/mobile/backdrop` sources vendor Kyant0/backdrop v2.0.0, copyright 2025 Kyant0, Apache-2.0 (full license in `android/licenses/backdrop-Apache-2.0.txt`). Source: https://github.com/Kyant0/backdrop. This version comes from BitChord commit b690dc146009d4815825fa4a20815ef2e4ee8898 and retains BitChord's reduced-resolution rendering additions. YTHub changes the package and adapts two GraphicsLayerScope properties for its Compose version. Source headers retain upstream attribution.

The material settings in `ReferenceGlass.kt` are adapted from BitChord's `LiquidGlass.kt`, GPL-3.0, itself adapted from Echo-Music. BitChord source: https://github.com/kushagrasinghx/BitChord; Echo source: https://github.com/EchoMusicApp/Echo-Music. These additions are used only by the GPL-3.0 Android application.
