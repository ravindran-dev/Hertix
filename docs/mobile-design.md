# Hertix mobile design system

Implementation: `android/app/src/main/java/dev/ravindran/ythub/mobile/MainActivity.kt`.

## Brand and foundations

Hertix — Download. Convert. Manage. The transparent red play/download mark is shared with the Windows app. The mobile interface uses bottom navigation and generous touch targets rather than a sidebar.

| Token | Dark | Light |
| --- | --- | --- |
| Background | `#0B0C10` | `#F6F5F3` |
| Surface | `#16181F` | `#FFFFFF` |
| Elevated surface | `#22252E` | `#EEECEF` |
| Primary | `#FF173D` | `#FF173D` |
| Text | `#F8F8FA` | `#171923` |
| Secondary text | `#A3A8B6` | `#696B77` |
| Success | `#34D399` | `#34D399` |

System sans-serif typography honors Android accessibility font sizing. Headings are bold; media titles wrap to two lines in lists. Horizontal page padding is 20 dp, cards have 20 dp radii, primary buttons have 16 dp radii and 56 dp minimum height. Icon actions use standard 48 dp Material touch targets. Insets protect content from the status/navigation bars. Lists scroll and thumbnail loading does not block interactions.

## Screen coverage

| Flow | Implementation |
| --- | --- |
| Splash | Branded launcher/window background followed by onboarding engine check |
| Onboarding | Logo, feature badges, engine check and Get started |
| Home | URL input, paste/clear, Analyze, MP3/MP4 selection, quality sheet, playlist toggles |
| Media analysis | Actual thumbnail, title, channel, available duration/views/date and quality chips |
| Audio / video | Separate format selector and quality sheet; 320 kbps audio default |
| Playlist | Item list with index, duration where available, ordering and ZIP options |
| Download manager | Active/Completed/Failed filters, real progress, engine transfer line, pause/cancel |
| Download details | State, source, destination, quality, results, Copy URL and Open folder |
| Completed | Green completion indicator, Open file, Open ZIP when available |
| History | Search, All/Audio/Video/Playlists filters and detail actions |
| Settings | Download, quality, playlist, appearance, notifications, storage and engine groups |
| Storage / folder picker | Device usage bar, history media totals, temp cleanup, native folder picker |
| Errors | Dismissible readable error dialog and retry from failed details |
| Empty states | Hertix mark, concise message and reachable Home action |
| Light / dark | Shared components using Material color tokens; System follows Android |

## Interaction rules

Analyze leads to a review screen before queue admission. Media download actions stay at the bottom. Quality/settings selectors use bottom sheets with radio choices. Cancellation has a confirmation dialog. Changes of main screen use a short cross transition; Material controls provide pressed/disabled feedback. The app uses real metadata and transfer progress; it does not seed fabricated downloads. Navigation actions all have icons and text labels; gestures are never required to access an action.

### Floating glass navigation · Android 1.0.1

The 76 dp navigation pill floats 16 dp from the screen edges above the Android gesture area. A shared GPU graphics layer supplies the actual screen behind the glass. Android 12+ applies a backdrop blur; older versions keep the translucent tinted surface and highlights. Sharp icons and labels are drawn separately above the blur. A translucent lens moves between the four tabs with a damped spring, samples its backdrop at 1.08× magnification, and enlarges the selected icon to 1.17×. The red accent, soft specular rim and shadow adapt to both themes. Tab semantics expose selected state and full-height touch targets. Scroll content has bottom padding so final rows remain reachable above the floating bar. System animation scaling controls the spring motion.

### Refined navigation · Android 1.0.2

The revised pill is 68 dp high with 20 dp side margins and a softer shadow. Four custom rounded line glyphs use a consistent 24-point grid and stroke weight. Labels keep a stable medium weight while the active accent transitions over 220 ms. The neutral glass lens slides with a nearly critically damped spring, smoothly stretches up to 14% in transit, and settles at the destination. Icon and label move as a single group: 0.92× while held and 1.04× for the active resting tab, with a one-dp lift. The default Material ripple is explicitly disabled; the rounded tab touch area uses only the custom press response. Actual backdrop blur remains separate from the sharp foreground glyphs. The glass lens uses a subtle 1.04× backdrop magnification rather than a strong red glow.

The mobile code and design tokens can guide an iOS implementation, but this deliverable builds an Android APK only. Live engine and device verification is recorded separately in the Android verification report.
