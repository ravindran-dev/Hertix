# Hertix brand assets and migration

Hertix uses the supplied transparent artwork in the repository root:

- `Neon Gradient H Music Logo.png` — gradient H/play symbol source.
- `Hertix Gradient Audio Logo.png` — wordmark source.

Generated assets are in `public/`:

- `hertix-symbol.png` — transparent symbol used by web and Android UI.
- `hertix-logo.png` and `hertix-logo-dark.png` — transparent supplied wordmark for dark surfaces.
- `hertix-icon.png` — dark-backed square icon.
- `hertix-favicon.png` — compact transparent favicon.
- `hertix-splash.png` — dark splash art with centered symbol.

The Windows Tauri package uses the generated multi-size platform artwork under `src-tauri/icons/`, including ICO and ICNS files. Android launcher resources are in `android/app/src/main/res/mipmap-*`; the adaptive icon uses a separately scaled foreground and the `#09090D` background. The app splash screen uses the transparent symbol on its dark system splash background. Android status-bar notifications use a monochrome H/play vector, as required by Android's notification icon rendering.

The brand palette is centralized in desktop CSS custom properties and `MobileUi` in `MusicDesign.kt`: violet `#702BFF`, purple `#9B35F5`, magenta `#E42BAF`, pink `#FF438A`, orange `#FF922E`, and near-black surfaces.

## Upgrade compatibility

The visible product name is now Hertix. The Android `applicationId` remains `dev.ravindran.ythub.mobile` and the Tauri identifier remains `dev.ravindran.ythub`. These identifiers, Android's encrypted account-session file, the `ythub-account-v1` Keystore alias, desktop application-data directory, local-storage keys, Supabase project/repository URLs and Rust package name remain stable so existing installs and user data can migrate in place. Existing `Downloads/YTHub` files are not renamed or moved; new Android exports use `Downloads/Hertix`.

The Android release workflow requires the stable signing secrets and the public Supabase build settings `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY`. The publishable key is client-safe; never use a service-role key in the application. A missing Supabase build setting disables cloud account features in the resulting APK, so the release workflow now checks that both values exist before building.
