# Troubleshooting

## Missing or blocked executable

Use Settings → Media engine → Check versions. Custom paths must be existing absolute `.exe` files. Antivirus interference, missing DLL dependencies, or incompatible architectures appear as version probe errors. Do not disable security tools; use a trusted upstream build or a permitted custom location.

## YouTube extraction

Update yt-dlp, verify Deno, and retry. A deleted/private video is not a network failure. Playlist entries can be unavailable independently. Configure a user-supplied cookies file only for content you can access. Network/bot restrictions from YouTube are surfaced as extractor errors; the app does not bypass them.

## Disk and permissions

The download, partial-data, and ZIP paths are created and write-probed. Settings can display free space. Audio estimates can reject an obviously oversized playlist; video size depends on format selection and is not guessed as an exact amount. A full disk during yt-dlp/FFmpeg execution fails the job with its process error. A ZIP disk failure leaves successful source files available for retry.

## Interrupted operations

Resume Interrupted jobs or playlists after restarting. Completed output filenames include the media ID by default to avoid same-title collisions. Rename mode adds a task identifier; overwrite is explicit. Pause during postprocessing may restart that processing step. Clearing terminal temporary data means a failed/cancelled task may need to redownload source data.

## Local state

Use Settings → Open logs and the engine diagnostics panel to inspect lifecycle outcomes and worker limits. SQLite state and its WAL companions are kept in Tauri's per-user app-data directory. Never send state files publicly: they can contain configured proxy information and cookie-file paths. Close Hertix and preserve the database and legacy JSON files before attempting manual recovery. See recovery.md.
