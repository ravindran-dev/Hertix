# Android third-party notices

Hertix Android source is licensed under GNU GPL v3. A copy is included in `LICENSE`.

- youtubedl-android 0.18.1 and its FFmpeg module: GPL-3.0, https://github.com/yausername/youtubedl-android. Source and native build instructions: upstream `BUILD_FFMPEG.md`, `BUILD_PYTHON.md`, and release sources. The distributed native artifacts contain Python, FFmpeg and QuickJS; their individual upstream notices also apply.
- yt-dlp: Unlicense for source; see https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE and upstream notices for components bundled in release artifacts.
- FFmpeg: LGPL/GPL depending on enabled components; this Android package uses the GPL wrapper's provided build. https://ffmpeg.org/legal.html.
- Python: Python Software Foundation license. https://docs.python.org/3/license.html.
- QuickJS: MIT license. https://bellard.org/quickjs/.
- AndroidX / Jetpack Compose / Kotlin / kotlinx.coroutines / Gson: Apache-2.0. Coil: Apache-2.0.
- Hertix branding and icon derive from the logo artwork supplied by the user.

Complete corresponding Android application source, Gradle files, pinned dependency coordinates and wrapper are in `android/`. Native dependency source/build information is available at the upstream repository above. Public redistribution requires preserving applicable notices and providing corresponding source as required by each license.

## Inter font

Inter is copyright The Inter Project Authors, licensed under the SIL Open Font License 1.1. The font is bundled for consistent sans-serif typography across device themes. License: `licenses/Inter-OFL.txt`. Source: https://github.com/google/fonts/tree/main/ofl/inter
