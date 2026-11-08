package dev.ravindran.ythub.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** LRCLIB lookup cache; successful lyrics are also persisted in the local track record. */
internal object LyricsRepository {
    private data class Entry(val lyrics: String?, val expiresAt: Long)
    private val entries = ConcurrentHashMap<String, Entry>()
    private const val cacheMs = 60L * 60L * 1000L
    private const val baseUrl = "https://lrclib.net/api/get"

    suspend fun find(track: LocalTrack): String? = withContext(Dispatchers.IO) {
        val title = track.title.trim()
        val artist = track.artist.trim()
        require(title.isNotEmpty() && artist.isNotEmpty() && !artist.equals("Unknown artist", true)) {
            "Track title and artist are needed to search for lyrics."
        }
        val key = listOf(title, artist, track.album, track.durationMs.toString()).joinToString("\u001f").lowercase()
        entries[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withContext it.lyrics }

        val params = linkedMapOf("track_name" to title, "artist_name" to artist)
        track.album.takeIf { it.isNotBlank() && !it.equals("Unknown album", true) }?.let { params["album_name"] = it }
        track.durationMs.takeIf { it > 0L }?.let { params["duration"] = ((it + 500L) / 1000L).toString() }
        val query = params.entries.joinToString("&") { (name, value) ->
            "${URLEncoder.encode(name, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        val connection = (URL("$baseUrl?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "YTHub-Android/1.0 (https://github.com/ravindran-dev/YTHub)")
        }
        try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> null
                HttpURLConnection.HTTP_OK -> JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).let { response ->
                    response.optString("syncedLyrics").takeIf { it.isNotBlank() }
                        ?: response.optString("plainLyrics").takeIf { it.isNotBlank() }
                }
                429, HttpURLConnection.HTTP_UNAVAILABLE -> error("Lyrics service is busy. Try again in a little while.")
                else -> error("Lyrics lookup failed ($status). Try again later.")
            }.also { lyrics -> entries[key] = Entry(lyrics, System.currentTimeMillis() + cacheMs) }
        } finally {
            connection.disconnect()
        }
    }
}
