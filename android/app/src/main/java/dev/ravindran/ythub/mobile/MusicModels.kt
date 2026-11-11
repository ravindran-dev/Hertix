package dev.ravindran.ythub.mobile

import java.io.File
import java.util.UUID

/** URI is the stable identity; titles are never used as keys. Download IDs reference DownloadStore. */
data class LocalTrack(
    val id: String, val uri: String, val title: String, val artist: String = "Unknown artist",
    val album: String = "Unknown album", val filename: String = "", val durationMs: Long = 0,
    val bytes: Long = 0, val bitrate: Long = 0, val format: String = "", val artwork: String = "",
    val downloadId: String? = null, val added: Long = System.currentTimeMillis(),
    val available: Boolean = true, val lyrics: String = "", val folder: String = "",
    /** Exact YouTube video for source-aware lyrics lookup (especially playlist downloads). */
    val sourceVideoUrl: String? = ""
)
data class LocalPlaylist(val id: String = UUID.randomUUID().toString(), val name: String, val tracks: List<String> = emptyList(), val downloadId: String? = null, val sourceTracks: List<String>? = null)
data class PlayRecord(val count: Int = 0, val lastPlayed: Long = 0)
data class PlayerPreferences(
    val autoAdd: Boolean = true, val autoPlayDownload: Boolean = false, val downloadPlaylists: Boolean = true,
    val notifyPlaylist: Boolean = true, val rememberPosition: Boolean = true, val restoreTrack: Boolean = true,
    val autoNext: Boolean = true, val background: Boolean = true, val speed: Float = 1f,
    val skipForward: Int = 10, val skipBackward: Int = 10, val libraryLayout: String = "Grid", val songsLayout: String = "Compact",
    val equalizer: Boolean = false, val preset: String = "Flat", val bands: List<Int> = emptyList(), val bass: Int = 0
)
data class MusicState(
    val version: Int = 2, val tracks: List<LocalTrack> = emptyList(), val playlists: List<LocalPlaylist> = emptyList(),
    val favorites: Set<String> = emptySet(), val hidden: Set<String> = emptySet(),
    val history: Map<String, PlayRecord> = emptyMap(), val queue: List<String> = emptyList(),
    val queueIndex: Int = 0, val position: Long = 0, val repeat: Int = 0, val shuffle: Boolean = false,
    val settings: PlayerPreferences = PlayerPreferences()
)
data class PlaybackSnapshot(
    val track: LocalTrack? = null, val playing: Boolean = false, val position: Long = 0, val duration: Long = 0,
    val queue: List<String> = emptyList(), val index: Int = 0, val shuffle: Boolean = false, val repeat: Int = 0,
    val error: String? = null, val sleepDeadline: Long = 0, val sleepMode: String = "Off",
    val equalizerBands: List<Int> = emptyList(), val equalizerRange: Pair<Int, Int> = -1500 to 1500,
    val effectsAvailable: Boolean = false, val bassAvailable: Boolean = false, val equalizerPresets: List<String> = emptyList(), val equalizerLevels: List<Int> = emptyList()
)
object MusicRules {
    val extensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
    private const val albumArtistSeparator = "\u001f"
    fun albumGroupKey(track: LocalTrack) = "${track.album}$albumArtistSeparator${track.artist}"
    fun albumGroupTitle(key: String) = key.substringBefore(albumArtistSeparator)
    fun albumGroupArtist(key: String) = key.substringAfter(albumArtistSeparator, "")
    fun audioName(name: String) = name.substringAfterLast('.', "").lowercase() in extensions
    fun merge(existing: List<LocalTrack>, incoming: List<LocalTrack>): List<LocalTrack> {
        val tracks = existing.associateBy { it.id }.toMutableMap()
        incoming.forEach { track ->
            val previous = tracks[track.id]
            tracks[track.id] = if (previous == null) track else track.copy(
                added = previous.added,
                lyrics = previous.lyrics.ifBlank { track.lyrics },
                sourceVideoUrl = track.sourceVideoUrl.orEmpty().ifBlank { previous.sourceVideoUrl.orEmpty() }
            )
        }
        return tracks.values.toList()
    }
    fun occurrenceIndex(source: List<String>, visible: List<String>, index: Int): Int {
        val id = visible.getOrNull(index) ?: return -1
        val occurrence = visible.take(index).count { it == id }
        return source.withIndex().filter { it.value == id }.getOrNull(occurrence)?.index ?: -1
    }
    fun restore(queue: List<String>, index: Int, tracks: List<LocalTrack>): Pair<List<String>, Int> {
        val available = tracks.filter { it.available }.map { it.id }.toSet()
        val current = queue.getOrNull(index)
        val valid = queue.filter { it in available }
        val before = queue.take(index.coerceAtLeast(0)).count { it in available }
        return valid to if (current in available) before else before.coerceAtMost((valid.size - 1).coerceAtLeast(0))
    }
    fun next(index: Int, size: Int, repeat: Int): Int? = when {
        size == 0 -> null
        repeat == 1 -> index
        index + 1 < size -> index + 1
        repeat == 2 -> 0
        else -> null
    }
    fun previous(index: Int, position: Long): Pair<Int, Long> = (if (position > 3000) index else (index - 1).coerceAtLeast(0)) to 0L
    fun shuffleUpcoming(queue: List<String>, index: Int, random: kotlin.random.Random = kotlin.random.Random.Default) = queue.take(index + 1) + queue.drop(index + 1).shuffled(random)
    fun archiveTarget(root: File, name: String): File {
        require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\\') && !Regex("^[A-Za-z]:").containsMatchIn(name)) { "Unsafe archive path" }
        val target = File(root, name).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator)) { "Archive path escapes destination" }
        return target
    }
    fun sleepExpired(deadline: Long, now: Long) = deadline > 0 && now >= deadline
}
data class LyricLine(val timeMs: Long, val text: String)
fun parseLyrics(text: String): List<LyricLine> {
    val tag = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
    return text.lines().flatMap { line ->
        val matches = tag.findAll(line).toList()
        matches.map { match ->
            val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
            LyricLine(match.groupValues[1].toLong() * 60000 + match.groupValues[2].toLong() * 1000 + fraction, line.replace(tag, "").trim())
        }
    }.sortedBy { it.timeMs }
}
