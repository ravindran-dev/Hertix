Hertix 1.0.23

- Search exact-video, regional catalog and transliterated title sources together, keeping responsive results and prioritizing synchronized lyrics.
- Add additional regional timed lyric catalogs and timed video-caption fallback when a catalog has no match.
- Refresh the music client version from its service page so exact-video lyric requests remain compatible as that service changes.
- Cancel an old track’s lyric lookup cleanly when playback changes, so results cannot overwrite the current lyric state.
- Keep each playlist track linked to its exact video so source-based lyrics lookup works for individual songs.
- Search lyrics catalogs with native-script and Latin transliterations for Tamil and other Indian languages.
- Prefer time-synchronized lyrics when available and avoid false line highlighting for plain lyrics.
- Replace saved plain lyrics with synchronized lyrics when a better match is found.
- Prevent a crash when older saved tracks are opened or rescanned after adding optional lyric source metadata.
- Keep older library records and backups compatible with the new lyric source field.

Hertix 1.0.22

- Regional lyrics search now tries original-script and Tanglish queries and checks several closely matched YouTube Music sources.
- Download playlists directly from YouTube search results.
- Playlist retries skip completed tracks and retry failed tracks only. Older jobs recover completed tracks from saved files.
- Show the Hertix symbol when song artwork is unavailable.
- Make contextual popups and update notices more translucent so the page behind them remains visible.
