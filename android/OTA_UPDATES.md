# Hertix Android over-the-air updates

Hertix checks the latest stable release in the existing `ravindran-dev/Hertix` repository on each fresh app launch and when the user opens App Updates. When a newer release is available, it shows a popup with **Download now** and **Remind me later** actions. Choosing **Remind me later** closes the popup for that launch; if an update is still available, Hertix offers it again on the next fresh launch. The first launch after v1.0.9 installs opens Account once; later launches use the regular Home destination. A release includes notes and a signed APK; Android's package installer asks the user to confirm installation.

## Publish an update

1. Increase `versionName` and `versionCode` in `android/app/build.gradle.kts`.
2. Keep the existing Android application ID `dev.ravindran.ythub.mobile` and the signing certificate used by installed releases. This lets Android apply the update in place and preserve account/session data.
3. Keep these GitHub Actions secrets configured: `YTHUB_RELEASE_KEYSTORE_BASE64`, `YTHUB_RELEASE_STORE_PASSWORD`, `YTHUB_RELEASE_KEY_ALIAS`, and `YTHUB_RELEASE_KEY_PASSWORD`.
4. Also configure the public client settings `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY`. The release workflow fails before building if either is absent; without them, account and cloud-sync functionality is compiled out of the application configuration.
5. Push a tag matching the version, such as `v1.0.18`. `.github/workflows/android-release.yml` builds and publishes a Hertix-named APK asset. The in-app checker reads the stable release API and selects the APK asset.

## v1.0.18

- Fix YouTube Music lyrics discovery for localized tabs, including Tamil, by matching the track-lyrics endpoint rather than only an English tab title.
- Show Tamil lyrics in Tanglish by default, with a toggle to read the original Tamil script.
- Refine the update popup with a compact frosted layout, scrollable release notes, and stacked Download / Remind actions.
- Improve YouTube downloads with concurrent fragment fetching, chunked HTTP transfers, and stronger network/fragment retry handling.
- Match the player menu's glass backdrop to the home menu, enlarge the home wordmark, and balance avatar/menu spacing.
- Keep Tanglish conversion available on Android 8–9 with a built-in Tamil transliteration fallback.

## v1.0.17

- YouTube Music discovery now uses the WEB_REMIX/Innertube search endpoint directly. It does not use the Google YouTube Data API quota bucket.
- Regional-language search uses the device locale and region.
- Lyrics matching now accepts SimpMusic's documented video-ID response fields, including rich synchronized and plain lyrics that omit title/artist metadata.
- The update notice now uses a compact frosted card, readable release notes, and stacked download/remind actions.
- Regional LRC matching accounts for the singer field returned by KuGou, so results with the provider's field naming are not discarded.
- YouTube Music lyrics-tab lookup and the exact-video transcript fallback use Innertube, including regional-script lyrics returned for the video itself.
- Replaced the Android launcher, splash and Hertix home wordmark assets with the newly supplied purple play H symbol and wordmark.

The account session stays in the package's `noBackupFilesDir`, encrypted with the Android Keystore alias `ythub-account-v1`. Both names intentionally remain unchanged across the Hertix rebrand. Do not change the application ID, session filename or key alias without an explicit account-data migration. Existing user files are not moved; new Android exports go to `Downloads/Hertix`, while existing `Downloads/YTHub` files remain accessible.

The updater downloads into app-private storage, imposes a 1 GB size cap, and shares the APK with Android's installer through the existing `FileProvider`. Android may require “Allow from this source” for Hertix. No separate SDK is used.
