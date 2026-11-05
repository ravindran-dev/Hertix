# Named YouTube playlist downloads

In the audio playlist preview, choose **Create a YTHub playlist** and enter a name. The choice and name are saved with the download. Turning it off overrides automatic playlist creation for that download.

The downloader records final audio filenames in YouTube order, independently of alphabetical export order or filename numbering. Playlist creation retains playable offline audio even when ZIP-only storage was selected. Successful songs from partial downloads are imported; retries fill missing songs without duplicating the playlist. Local renames, removals and additions are preserved. Playlist analysis no longer truncates the source to 500 entries. Unavailable videos still report download failures.

Final filenames use yt-dlp's documented [after_move filepath output](https://github.com/yt-dlp/yt-dlp#output-template).

Page headings and the scenic background now move with consumed scrolling. Glass header actions, contextual library/player actions and the bottom dock remain fixed. Download summary/filter controls and the download action scroll with their page.

Validation: 45 JVM tests, nine physical-phone tests, Android lint, debug/instrumentation builds and the non-debug preview build passed. Device coverage includes custom-name validation and opt-out, actual local audio import with automatic library additions disabled, partial-download retry ordering, duplicate song occurrences, fixed glass actions and moving headings/background, plus existing player/navigation/backup regressions. YouTube network downloading was not exercised with a new live playlist during this validation.
