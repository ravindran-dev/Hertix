# BitChord glass adaptation

Reference: BitChord b690dc146009d4815825fa4a20815ef2e4ee8898, inspected in source and on the connected phone.

The floating dock uses the vendored Kyant0/backdrop renderer with BitChord-derived material settings: 8 dp blur, 1.5 saturation, 24 dp lens height and amount, 0.4 surface opacity and directional Highlight.Default/Shadow.Default. Processing resolution is 0.25. Android 13+ uses depth refraction while idle; refraction is suspended during library scrolling and chromatic dispersion is disabled. Android 12 uses blur/highlights; older versions retain a filled fallback. Top controls, panels and popovers sample one shared frosted ambient scene with a native rim/shadow instead of running separate shader chains. The dock still samples live cropped content, reusing one foreground render node; capture is capped at 30 Hz during library scrolling without limiting foreground scrolling.

The navigation indicator now travels on a spring and stretches in proportion to travel lag. Container press feedback remains spring driven. Icons, label contrast and capsule insets follow the reference proportions. The expanded player hides the dock, uses a visible square album cover over an ambient artwork background, with theme-aware progress/volume tracks and Lyrics/Output/Queue actions. Queue opening no longer enqueues another copy of the current song.

Licensing and source modifications are recorded in THIRD_PARTY_NOTICES.md and android/licenses/backdrop-Apache-2.0.txt. The vendored source retains its headers. Two GraphicsLayerScope properties were adapted for YTHub's Compose version and shader construction has an explicit Android 13 guard.

Validation: debug APK assembly, all 32 JVM tests, lint and diff whitespace checks passed. Installed using adb install -r. Inspected dock and full player screenshots on the phone, with no AndroidRuntime fatal exception observed. Older Android fallback rendering and performance benchmarks were not device tested. This adapts the reference material and layout; it does not claim every screen is a pixel-identical copy of BitChord.

Smoothness follow-up: see [SMOOTHNESS_PLAYER_FIX.md](SMOOTHNESS_PLAYER_FIX.md) for the current phone checks and frame measurements.
