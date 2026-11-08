# Concurrency and resource budgets

Defaults: two downloads, two processing workers, a YouTube host limit of three, four requested fragments per download, 24 aggregate fragment permits, adaptive scheduling enabled, unlimited bandwidth, and one independent ZIP slot. Normal download concurrency is 1–6; advanced mode explicitly permits up to 12. Processing is 1–4. Reusable Tokio worker tasks are idle until assigned work; these numbers do not mean dedicated OS threads or that all worker subprocesses start together.

Every download holds a global download permit, a host permit, and fragment permits. The actual fragment argument is `min(requested_fragments, aggregate_fragment_budget / configured_download_slots)`, at least one. For four downloads and four fragments, up to 16 fragment operations are requested, rather than an unbounded request multiplier. A host limit below four can further reduce simultaneous downloads. Fragments are yt-dlp native DASH/HLS concurrency, not another pool of full-video jobs.

YouTube is one logical source host: its CDN requests share this pipeline budget. Hertix does not claim to meter every individual CDN hostname, metadata request, thumbnail byte or TCP connection.

An optional total bandwidth limit is divided by the configured maximum download slots and passed as yt-dlp `--limit-rate` in bytes per second. This conservative allocation does not redistribute spare capacity when fewer workers run. It bounds the configured media download allowances; it is not a packet-level shaper. Pause active work before changing download/bandwidth/fragment budgets so already-running commands cannot retain conflicting limits.

Adaptive scheduling initially admits at most two downloads, reduces admission under CPU/memory pressure or HTTP throttling, and restores one slot after 30 seconds of healthy operation. A throttled host receives a growing cooldown; already-running success cannot clear an unexpired cooldown. Metadata admission is separately limited to one.

Queue selection respects High, Normal and Low priorities, then least-recently-served playlist group and original index. Waiting jobs age toward high priority after a bounded number of dispatches so sustained priority traffic cannot indefinitely exclude them. New playlist windows are staged before filling already well-represented groups. Processing and ZIP use their own permits and cannot consume a download slot.

Disk reservations account for source and final media, active reservations on the same volume, and a free-space safety reserve (default 256 MiB). Estimates are conservative admission estimates, not promised file sizes. Processes recheck free space every two seconds; archives check during streaming. FFmpeg decoding/encoding thread arguments are bounded to two, separate from process concurrency.
