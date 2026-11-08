# Music library presentation

Hertix uses an artwork-first hierarchy, flat lists, quiet metadata, compact overflow controls, restrained accent color and clear section spacing through its own Compose components.

Changes:
- The Home scenic background is shared across browsing routes at the user’s request; full-player artwork remains its own backdrop. Surface and text colors use neutral charcoal/gray tones.
- Artwork uses square, softly rounded crops. Missing artwork uses a stable muted palette and subtle album motif rather than a large branded red tile. No metadata or remote artwork is fabricated.
- Artwork cards place one-line titles and secondary metadata beneath the image without an outer bordered container. Shelves use 150 dp covers.
- Playlists use square covers, four-cover collages when available, song counts and a duration line. Song rows are flat with inset separators; the current track receives a subtle tint and indicator. Long-press selection, overflow actions, duration and playback routing are retained.
- Albums use a two-column grid and real artist/song counts. Unknown album metadata is presented as Unsorted songs without changing stored metadata. Artists use circular cover presentation. Album and artist detail pages include a focused cover header.
- Floating glass navigation, bottom list padding and offline/online backup behavior remain integrated.

Header/player follow-up: the account avatar is a native AccountCircle icon centered between Add and More, with 48 dp touch targets. Account was removed from the overflow menu to avoid duplicate navigation. The mini-player includes Previous, Play/Pause and Next. Playing songs receive a draw-only animated equalizer, a leading accent marker and a Playing label; paused songs use a static paused state. Artwork cards also show a compact playback badge.

Validation: 39 unit tests, debug/instrumentation builds and lint passed; four phone regression tests passed. Avatar alignment and Previous-button presence were checked on the phone. A running song’s visible notation was verified through UI semantics and a screenshot. The final icon replacement was built, installed and visually checked.
