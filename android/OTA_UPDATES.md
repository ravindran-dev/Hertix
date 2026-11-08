# Hertix Android over-the-air updates

Hertix checks the latest stable release in the existing `ravindran-dev/Hertix` repository on each fresh app launch and when the user opens App Updates. When a newer release is available, it shows a popup with **Download now** and **Remind me later** actions. Choosing **Remind me later** closes the popup for that launch; if an update is still available, Hertix offers it again on the next fresh launch. The first launch after v1.0.9 installs opens Account once; later launches use the regular Home destination. A release includes notes and a signed APK; Android's package installer asks the user to confirm installation.

## Publish an update

1. Increase `versionName` and `versionCode` in `android/app/build.gradle.kts`.
2. Keep the existing Android application ID `dev.ravindran.ythub.mobile` and the signing certificate used by installed releases. This lets Android apply the update in place and preserve account/session data.
3. Keep these GitHub Actions secrets configured: `YTHUB_RELEASE_KEYSTORE_BASE64`, `YTHUB_RELEASE_STORE_PASSWORD`, `YTHUB_RELEASE_KEY_ALIAS`, and `YTHUB_RELEASE_KEY_PASSWORD`.
4. Also configure the public client settings `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY`. The release workflow fails before building if either is absent; without them, account and cloud-sync functionality is compiled out of the application configuration.
5. Push a tag matching the version, such as `v1.0.14`. `.github/workflows/android-release.yml` builds and publishes a Hertix-named APK asset. The in-app checker reads the stable release API and selects the APK asset.

The account session stays in the package's `noBackupFilesDir`, encrypted with the Android Keystore alias `ythub-account-v1`. Both names intentionally remain unchanged across the Hertix rebrand. Do not change the application ID, session filename or key alias without an explicit account-data migration. Existing user files are not moved; new Android exports go to `Downloads/Hertix`, while existing `Downloads/YTHub` files remain accessible.

The updater downloads into app-private storage, imposes a 1 GB size cap, and shares the APK with Android's installer through the existing `FileProvider`. Android may require “Allow from this source” for Hertix. No separate SDK is used.
