package dev.ravindran.ythub.mobile

import java.net.URI
import java.util.Locale

data class Preferences(
    val theme: String = "Dark", val audioQuality: String = "320", val videoQuality: String = "best",
    val concurrency: Int = 2, val autoStart: Boolean = true, val preserveOrder: Boolean = true,
    val autoZip: Boolean = false, val keepFolder: Boolean = true, val notifications: Boolean = true,
    val folderUri: String = "", val folderName: String = "Downloads / Hertix", val retries: Int = 3,
    val proxy: String = "", val cookiesPath: String = "", val onboardingDone: Boolean = false
)
/** One official engine check per day, including recovery from a rolled-back system clock. */
internal fun engineUpdateDue(now: Long, checked: Long): Boolean = checked <= 0L || checked > now || now - checked >= 24L * 60 * 60 * 1000
data class MediaInfo(
    val url: String, val title: String, val channel: String = "", val thumbnail: String = "",
    val duration: Long = 0, val views: Long = 0, val uploadDate: String = "", val playlist: Boolean = false,
    val entries: List<MediaInfo> = emptyList(), val qualities: List<Int> = emptyList(), val count: Int = 1
)
data class DownloadItem(
    val id: String, val media: MediaInfo, val audio: Boolean, val quality: String,
    val created: Long = System.currentTimeMillis(), val state: String = "Queued", val progress: Float = 0f,
    val line: String = "Waiting for a download slot", val eta: Long = 0, val bytes: Long = 0,
    val uris: List<String> = emptyList(), val mime: String = "", val zipUri: String = "", val error: String = "",
    val options: Preferences = Preferences(), val completedCount: Int = 0, val failedCount: Int = 0,
    val exportedNames: List<String> = emptyList(),
    val createLibraryPlaylist: Boolean? = null, val libraryPlaylistName: String? = null, val playlistFileOrder: List<String>? = null,
    val cloudRecord: String? = null, val accountId: String? = null, val playWhenReady: Boolean = false, val cloudAttempts: Int = 0, val retryAfter: Long = 0,
    val localCopyDeleted: Boolean = false
) {
    val terminal: Boolean get() = state in listOf("Completed", "Failed", "Cancelled")
    val working: Boolean get() = state in listOf("Downloading", "Processing", "Exporting", "Zipping")
}
fun validYouTubeUrl(value: String): String {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: error("Enter a valid YouTube URL.")
    val host = uri.host?.lowercase(Locale.ROOT) ?: error("Enter a full YouTube URL including https://.")
    require(uri.scheme == "https" && (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com"))) {
        "Use an HTTPS link from youtube.com or youtu.be."
    }
    require(uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "This YouTube link is not supported." }
    return uri.toASCIIString()
}
fun bytesLabel(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val unit = if (bytes < 1024 * 1024) "KB" else if (bytes < 1024L * 1024 * 1024) "MB" else "GB"
    val divisor = when (unit) { "KB" -> 1024.0; "MB" -> 1024.0 * 1024; else -> 1024.0 * 1024 * 1024 }
    return String.format(Locale.US, "%.1f %s", bytes / divisor, unit)
}
fun durationLabel(seconds: Long): String = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
fun qualityLabel(audio: Boolean, quality: String): String = if (quality == "best") "Best available" else if (audio) "$quality kbps" else "${quality}p"
