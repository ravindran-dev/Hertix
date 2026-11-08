# Android glass and navigation review

The floating dock uses the vendored Kyant0/backdrop renderer with a restrained glass treatment: 8 dp blur, 1.5 saturation, 24 dp lens height and amount, 0.4 surface opacity and directional highlights/shadows. Processing resolution is 0.25. Android 13+ uses depth refraction while idle; refraction is suspended during library scrolling and chromatic dispersion is disabled. Android 12 uses blur/highlights; older versions retain a filled fallback. Top controls, panels and popovers sample one shared frosted ambient scene with a native rim/shadow instead of running separate shader chains. The dock samples live cropped content, reusing one foreground render node; capture is capped at 30 Hz during library scrolling without limiting foreground scrolling.

The navigation indicator travels on a spring and stretches in proportion to travel lag. Container press feedback remains spring driven. The expanded player hides the dock and uses a square album cover with theme-aware progress/volume tracks and Lyrics/Output/Queue actions. Queue opening does not enqueue another copy of the current song.

Licensing and source modifications are recorded in THIRD_PARTY_NOTICES.md and android/licenses/backdrop-Apache-2.0.txt. Vendored source retains its required license notices. Two GraphicsLayerScope properties were adapted for the app's Compose version, and shader construction has an explicit Android 13 guard.

Validation at the time of review: debug APK assembly, JVM tests, lint and diff whitespace checks passed. Older Android fallback rendering and performance benchmarks were not device tested.

Smoothness follow-up: see [SMOOTHNESS_PLAYER_FIX.md](SMOOTHNESS_PLAYER_FIX.md) for phone checks and frame measurements.
