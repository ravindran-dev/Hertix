# Download engine

Hertix runs entirely on the local Windows computer. React sends explicit Tauri commands; Rust owns admission, queues, persistence, subprocesses, resource limits, retries, and archives. Browser preview cannot download media.

## Pipeline

1. Validate the YouTube URL, quality, writable directories, settings, and admission limits. One metadata operation runs at a time.
2. Stream flat playlist metadata into an application-owned JSONL manifest, capped at 10,000 entries. The dashboard preview is limited to 100 entries; enqueue discovers the full playlist. Manifests retain source order, compact URLs, titles, indices and duration.
3. Materialize a bounded window (default 32, configurable 8–64) into durable jobs. Existing pending SQLite jobs are restored into the same window. Playlist cursor and staged jobs commit together.
4. Download source formats with bounded yt-dlp workers. Audio uses `bestaudio/best`; video downloads video and audio separately using comma format selection. `--fixup never`, native downloading and no extraction/remux options keep FFmpeg out of the download stage. Format filenames include format IDs.
5. Persist the source paths in `StagedMedia`, release the download permits, and queue independent processing. FFmpeg converts MP3 or merges/remuxes video. Metadata and optional cover art use explicit argument arrays.
6. Write to a same-directory `.part` output, verify required streams and positive duration using FFprobe, and atomically replace the final file on Windows. Existing files selected for skip/resume also undergo media validation.
7. Once every playlist item is terminal, queue ZIP independently. ZIP64 stores compressed media without wasting CPU recompressing it, streams 1 MiB chunks, preserves playlist index order, checks cancellation and disk space, and publishes only a finished archive. Incomplete exports require the explicit setting and receive an incomplete filename.

## Modules

`manager.rs` coordinates the scheduler. `download/manager_workers.rs`, `manager_control.rs`, and `manager_archive.rs` implement its phase execution, controls and archive coordination. `worker.rs` provides reusable bounded pools; `queue.rs` provides priority and playlist fairness; `scheduler.rs` manages adaptive limits and host cooldowns. `retry.rs`, `rate_limit.rs`, `resource_manager.rs`, `progress.rs`, `supervisor.rs`, `yt_dlp.rs`, and `ffmpeg.rs` each own one concern. `playlist.rs` streams metadata and reads bounded windows. `storage.rs` owns SQLite persistence and history paging.

The scheduler wakes on commands and worker completion, with a two-second health/retry heartbeat. It does not spawn a task for each of 10,000 playlist entries. Channel capacity and semaphore permits bound admitted phase work. Worker panic containment keeps a reusable pool alive.

## Observability

Progress comes from structured yt-dlp bytes and FFmpeg output timestamps. A bounded rolling sample window smooths speed; unknown totals or insufficient samples leave ETA unavailable. UI job patches omit settings and staged-file lists and are throttled to 1–10 Hz (default 5). Lifecycle changes emit immediately. Playlist counters include persisted rows outside the visible window. History is paged in groups of 200.

Settings shows active download, processing and ZIP workers; queued tasks; effective/configured concurrency; process IDs; rolling throughput and session peak; retries, cooldowns, completion counts, system CPU and memory pressure. Disk write throughput is explicitly **not measured**, rather than fabricated. Lifecycle logs rotate at 5 MB and record task, phase and exit outcomes without credentials or complete command lines.

See [concurrency](concurrency.md), [reliability](reliability.md), [recovery](recovery.md), the [pre-change audit](backend-audit.md), and [verification](verification.md).

Upstream behavior references: [yt-dlp options and format selection](https://github.com/yt-dlp/yt-dlp/blob/master/README.md), [SQLite WAL](https://www.sqlite.org/wal.html).
