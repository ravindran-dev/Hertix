package dev.ravindran.ythub.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Metadata-tolerant LRCLIB lookup; positive results are persisted with the local track. */
internal object LyricsRepository {
    private data class Entry(val lyrics: String?, val expiresAt: Long)
    private data class Record(
        val id: Long,
        val title: String,
        val artist: String,
        val durationSeconds: Double,
        val instrumental: Boolean,
        val syncedLyrics: String?,
        val plainLyrics: String?
    ) {
        val lyrics: String? get() = syncedLyrics ?: plainLyrics
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private const val successCacheMs = 24L * 60L * 60L * 1000L
    private const val missCacheMs = 10L * 60L * 1000L
    private const val baseUrl = "https://lrclib.net/api"
    private val removableTitleWords = setOf(
        "official", "video", "audio", "lyrics", "lyric", "visualizer", "music", "hd", "4k",
        "remaster", "remastered", "version", "topic", "youtube", "vevo", "full"
    )

    suspend fun find(track: LocalTrack, forceLookup: Boolean = false): String? = withContext(Dispatchers.IO) {
        val originalTitle = track.title.trim()
        val originalArtist = track.artist.trim()
        require(originalTitle.isNotEmpty()) { "Track title is needed to search for lyrics." }

        val key = listOf(originalTitle, originalArtist, track.album, track.durationMs.toString())
            .joinToString("\u001f").lowercase(Locale.ROOT)
        if (forceLookup) entries.remove(key)
        else entries[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withContext it.lyrics }

        val title = stripDecorations(originalTitle)
        val artist = cleanArtist(originalArtist)
        val duration = track.durationMs.takeIf { it in 1_000L..3_600_000L }?.let { (it + 500L) / 1_000L }

        // /get gives the most precise match, but LRCLIB deliberately requires duration
        // to be within about two seconds. Search below recovers from container/intro offsets.
        val exactParams = linkedMapOf("track_name" to originalTitle)
        originalArtist.takeIf(::knownArtist)?.let { exactParams["artist_name"] = it }
        track.album.takeIf { it.isNotBlank() && !it.equals("Unknown album", true) }?.let { exactParams["album_name"] = it }
        duration?.let { exactParams["duration"] = it.toString() }
        val exact = if (knownArtist(originalArtist)) getRecord("$baseUrl/get", exactParams) else LookupResult.Missing
        if (exact is LookupResult.Found) return@withContext cache(key, exact.record.lyrics)

        // LRCLIB search does not accept duration; rank results locally so a broad query
        // cannot silently attach another song with a similar title.
        delay(300)
        val searchParams = linkedMapOf("track_name" to title)
        artist.takeIf(::knownArtist)?.let { searchParams["artist_name"] = it }
        val records = searchRecords(searchParams)
        val best = selectBest(records, title, artist, duration)
        if (best != null) return@withContext cache(key, best.lyrics)

        // Imported/downloaded tracks can carry a label or channel as the artist. A
        // title-only retry is accepted only for an almost exact title and near-exact
        // duration, which is safer than trusting a mismatched artist tag.
        if (knownArtist(artist) && duration != null) {
            delay(300)
            val titleOnly = searchRecords(linkedMapOf("track_name" to title))
            val durationMatch = selectBest(titleOnly, title, "", duration)
            if (durationMatch != null) return@withContext cache(key, durationMatch.lyrics)
        }

        // Some catalog entries use the metadata provider's alternate title. Retry with a
        // cleaned signature, and only accept a close title/artist/duration match.
        if (title != originalTitle && knownArtist(artist)) {
            delay(300)
            val alternate = getRecord("$baseUrl/get", linkedMapOf(
                "track_name" to title,
                "artist_name" to artist
            ))
            if (alternate is LookupResult.Found && selectBest(listOf(alternate.record), title, artist, duration) != null) {
                return@withContext cache(key, alternate.record.lyrics)
            }
        }

        cache(key, null)
    }

    private sealed interface LookupResult {
        data class Found(val record: Record) : LookupResult
        data object Missing : LookupResult
    }

    private fun cache(key: String, lyrics: String?): String? {
        val ttl = if (lyrics.isNullOrBlank()) missCacheMs else successCacheMs
        entries[key] = Entry(lyrics?.takeIf { it.isNotBlank() }, System.currentTimeMillis() + ttl)
        return lyrics?.takeIf { it.isNotBlank() }
    }

    private fun getRecord(endpoint: String, params: Map<String, String>): LookupResult {
        val statusAndBody = request(endpoint, params)
        return when (statusAndBody.first) {
            HttpURLConnection.HTTP_NOT_FOUND -> LookupResult.Missing
            HttpURLConnection.HTTP_OK -> LookupResult.Found(parseRecord(JSONObject(statusAndBody.second)))
            else -> throw IllegalStateException("Lyrics lookup failed (${statusAndBody.first}). Try again later.")
        }
    }

    private fun searchRecords(params: Map<String, String>): List<Record> {
        val (status, body) = request("$baseUrl/search", params)
        when (status) {
            HttpURLConnection.HTTP_OK -> Unit
            429, HttpURLConnection.HTTP_UNAVAILABLE -> error("Lyrics service is busy. Try again in a little while.")
            else -> error("Lyrics search failed ($status). Try again later.")
        }
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { index ->
            runCatching { parseRecord(array.getJSONObject(index)) }.getOrNull()
        }
    }

    private fun parseRecord(json: JSONObject) = Record(
        id = json.optLong("id"),
        title = json.optString("trackName").takeIf { it.isNotBlank() && it != "null" }
            ?: json.optString("name").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
        artist = json.optString("artistName").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
        durationSeconds = json.optDouble("duration", 0.0).takeIf { it.isFinite() } ?: 0.0,
        instrumental = json.optBoolean("instrumental", false),
        syncedLyrics = json.optString("syncedLyrics").cleanLyrics(),
        plainLyrics = json.optString("plainLyrics").cleanLyrics()
    )

    private fun String?.cleanLyrics(): String? = this?.takeIf { it.isNotBlank() && it != "null" }

    private data class Scored(val record: Record, val score: Double, val titleScore: Double, val artistScore: Double, val durationDelta: Double)

    private fun selectBest(records: List<Record>, requestedTitle: String, requestedArtist: String, requestedDuration: Long?): Record? {
        val titleTokens = tokens(requestedTitle)
        val artistTokens = tokens(requestedArtist)
        if (titleTokens.isEmpty()) return null

        val candidates = records.mapNotNull { record ->
            if (record.instrumental || record.lyrics.isNullOrBlank()) return@mapNotNull null
            val candidateTitle = tokens(record.title)
            if (candidateTitle.isEmpty()) return@mapNotNull null
            val titleScore = tokenScore(titleTokens, candidateTitle)
            val candidateArtist = tokens(record.artist)
            val artistScore = if (artistTokens.isEmpty()) 0.0 else tokenScore(artistTokens, candidateArtist)
            val durationDelta = if (requestedDuration != null && record.durationSeconds > 0.0) {
                kotlin.math.abs(record.durationSeconds - requestedDuration)
            } else Double.POSITIVE_INFINITY

            // With artist metadata, demand a substantial title and artist match. If the
            // local file has no artist tag, require an almost exact title plus close duration.
            val titleAccepted = titleScore >= if (artistTokens.isEmpty()) .92 else .70
            val artistAccepted = artistTokens.isEmpty() || artistScore >= .45
            val durationAccepted = requestedDuration == null || durationDelta <= 20.0 || durationDelta.isInfinite()
            if (!titleAccepted || !artistAccepted || !durationAccepted) return@mapNotNull null
            val durationBonus = if (durationDelta.isFinite()) (1.0 - durationDelta.coerceAtMost(30.0) / 30.0) * .12 else 0.0
            Scored(record, titleScore * .65 + artistScore * .35 + durationBonus, titleScore, artistScore, durationDelta)
        }.sortedByDescending { it.score }

        val top = candidates.firstOrNull() ?: return null
        if (artistTokens.isEmpty() && (top.titleScore < .98 || top.durationDelta > 8.0)) return null
        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null && top.record.id != runnerUp.record.id && top.score - runnerUp.score < .06) return null
        return top.record
    }

    private fun tokenScore(requested: Set<String>, candidate: Set<String>): Double {
        if (requested.isEmpty() || candidate.isEmpty()) return 0.0
        val intersection = requested.intersect(candidate).size.toDouble()
        val recall = intersection / requested.size
        val precision = intersection / candidate.size
        return if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
    }

    private fun tokens(value: String): Set<String> = normalize(value)
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() && it !in removableTitleWords && it !in setOf("feat", "featuring", "ft") }
        .toSet()

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun stripDecorations(value: String): String = value
        .replace(Regex("\\([^)]*(official|video|audio|lyrics|visualizer|4k|hd|remaster)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\[[^]]*(official|video|audio|lyrics|visualizer|4k|hd|remaster)[^]]*]", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun cleanArtist(value: String): String = value
        .replace(Regex("\\s*-\\s*topic$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*\\|.*$"), "")
        .trim()

    private fun knownArtist(value: String) = value.isNotBlank() &&
        !value.equals("Unknown artist", true) && !value.equals("Various Artists", true) && value != "null"

    private fun request(endpoint: String, params: Map<String, String>): Pair<Int, String> {
        val query = params.entries.joinToString("&") { (name, value) ->
            "${URLEncoder.encode(name, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        val connection = (URL("$endpoint?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Hertix-Android/${BuildConfig.VERSION_NAME} (https://github.com/ravindran-dev/YTHub)")
        }
        try {
            val status = connection.responseCode
            if (status == 429 || status == HttpURLConnection.HTTP_UNAVAILABLE) {
                val waitSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
                throw IllegalStateException(if (waitSeconds != null) "Lyrics service is busy. Try again in $waitSeconds seconds." else "Lyrics service is busy. Try again later.")
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to body
        } finally {
            connection.disconnect()
        }
    }
}
