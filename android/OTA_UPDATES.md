# Android over-the-air app updates

YTHub checks the latest stable release in `ravindran-dev/YTHub` when the About page opens. If a newer semantic version exists, the page shows its release notes and offers an APK download. Android's package installer then asks the user to confirm installation; Android does not allow ordinary apps to silently install APK updates.

## Publish an update

1. Keep `versionName` in `android/app/build.gradle.kts` on semantic versions (`1.0.4`, `1.1.0`, etc.). The `versionCode` follows `major × 1,000,000 + minor × 1,000 + patch` and must always increase.
2. Build the signed APK with the same signing certificate used for every prior YTHub release.
3. Push a tag such as `v1.0.4`. `.github/workflows/android-release.yml` builds the release APK and attaches it to a GitHub Release. The in-app checker reads the release API and selects its `.apk` asset.
4. Configure these GitHub Actions repository secrets before publishing: `YTHUB_RELEASE_KEYSTORE_BASE64`, `YTHUB_RELEASE_STORE_PASSWORD`, `YTHUB_RELEASE_KEY_ALIAS`, and `YTHUB_RELEASE_KEY_PASSWORD`.

The signing key is essential: Android only installs an APK over an existing app when the package name and signing certificate match. Keep one private keystore backed up securely and use it for all release builds. Never commit it or its passwords. APKs built with another signing key cannot update in place; those users must uninstall and reinstall, which removes app-private data. The current preview APK uses this workstation's Android debug key, so a release must use that same key if those preview installs are expected to update in place. For a public production channel, distribute only builds signed with the stable release key from the start.

The updater downloads into app-private storage, imposes a 1 GB size cap, and shares the APK with Android's installer through the existing `FileProvider`. If Android requests it, users must enable “Allow from this source” for YTHub before installing. Updates are checked on demand when About opens and can be checked again from the same page.

YTHub uses its own native update client; no separate BitChord SDK is required. The interaction follows the public BitChord release flow at [BitChord Releases](https://github.com/kushagrasinghx/BitChord/releases), which describes in-app update checking, APK download/install, and release notes.
