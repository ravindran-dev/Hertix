# Persistence and recovery

Application state lives in `%APPDATA%/dev.ravindran.ythub/state.sqlite3`, resolved through Tauri's per-user application-data directory. SQLite uses WAL and `synchronous=FULL`. Settings, job rows and batch rows update transactionally; changed-row fingerprints prevent unchanged rows from being rewritten. No full JSON history rewrite occurs on progress. Persistence runs on blocking workers outside the main database mutex; lifecycle transitions save immediately and progress checkpoints occur every ten seconds.

Keep the SQLite file and its `-wal`/`-shm` companions together when backing up a running application; closing Hertix before copying is simplest. Do not delete a WAL file to repair state.

On first migration, Hertix imports the former `state.json`, falling back to `state.backup.json` when necessary. Both legacy files are preserved. An incomplete schema-only migration is detected and retried. Legacy import may briefly load the old JSON in full; normal operation uses bounded windows and paged history. Database corruption returns an error and preserves existing files instead of silently clearing history.

At startup, all unfinished running or queued persisted jobs become **Interrupted**. Source paths, isolated partial directories, playlist manifest paths/cursors and settings snapshots remain recorded. The in-memory startup history window is limited to 232 job records. Resume a job or playlist explicitly; yt-dlp continues compatible partial data, and a job with retained downloaded sources goes directly to processing. A missing source file fails visibly; it is not presented as a completed output.

The manifest cursor and newly staged jobs are saved in one transaction, so restart cannot advance past unsaved staged entries. Deterministic playlist task IDs include the batch UUID and original index. Jobs outside the visible window stay in SQLite and are restored fairly into the staging window. History APIs return at most 200 records per page.

Completed output files are retained. Interrupted ZIPs are not treated as completed archives; resume/retry rebuilds from successfully recorded media. Temporary cleanup accepts only recorded, managed task IDs directly beneath their canonical configured temporary root and requires active work to be paused. It never recursively removes an arbitrary settings directory.

SQLite WAL atomicity is described in the [upstream WAL documentation](https://www.sqlite.org/wal.html); the guarantees still depend on functioning storage and filesystem sync behavior.
