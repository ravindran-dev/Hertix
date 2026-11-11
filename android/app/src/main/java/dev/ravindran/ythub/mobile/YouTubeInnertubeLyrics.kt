package dev.ravindran.ythub.mobile

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Exact-video YouTube Music lyrics and transcript lookups through Innertube.
 * These endpoints often contain lyrics in
 * languages not covered by title-indexed catalogs.
 */
internal object YouTubeInnertubeLyrics {
    private const val FALLBACK_VERSION = "1.20260707.12.00"
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")
    @Volatile private var cachedClientVersion: String = FALLBACK_VERSION
    @Volatile private var clientVersionCachedAt: Long = 0L

    suspend fun lyrics(videoUrl: String): String? = withContext(Dispatchers.IO) {
        val videoId = videoId(videoUrl) ?: return@withContext null
        val next = runCatching {
            post("next", JSONObject()
                .put("videoId", videoId)
                .put("playlistId", "RDAMVM$videoId")
                .put("isAudioOnly", true))
        }.getOrNull() ?: return@withContext null

        // YouTube localizes tab titles (e.g. Tamil "பாடல் வரி"), so identify
        // the lyrics page by its stable page type / MPLY browse ID instead.
        val endpoint = findObjects(next, "tabRenderer").firstNotNullOfOrNull { tab ->
            val browse = findObjects(tab.opt("endpoint"), "browseEndpoint").firstOrNull()
                ?: return@firstNotNullOfOrNull null
            val browseId = browse.optString("browseId")
            val pageType = findString(tab, "pageType")
            val title = jsonText(tab.opt("title")).trim()
            browse.takeIf {
                pageType == "MUSIC_PAGE_TYPE_TRACK_LYRICS" ||
                    browseId.startsWith("MPLY") ||
                    title.equals("Lyrics", ignoreCase = true)
            }
        } ?: return@withContext null
        val browseId = endpoint.optString("browseId").takeIf(String::isNotBlank) ?: return@withContext null
        val request = JSONObject().put("browseId", browseId)
        endpoint.optString("params").takeIf(String::isNotBlank)?.let { request.put("params", it) }
        val page = runCatching { post("browse", request) }.getOrNull() ?: return@withContext null
        findObjects(page, "musicDescriptionShelfRenderer").firstNotNullOfOrNull { shelf ->
            jsonText(shelf.opt("description")).trim().takeIf(String::isNotBlank)
        }
    }

    /** Last-resort exact-video transcript provider. */
    suspend fun transcript(videoUrl: String): String? = withContext(Dispatchers.IO) {
        val videoId = videoId(videoUrl) ?: return@withContext null
        val bytes = byteArrayOf(10, videoId.toByteArray(Charsets.UTF_8).size.toByte()) + videoId.toByteArray(Charsets.UTF_8)
        val params = Base64.getEncoder().encodeToString(bytes)
        val response = runCatching { post("get_transcript", JSONObject().put("params", params)) }.getOrNull()
            ?: return@withContext null
        val cues = findObjects(response, "transcriptCueRenderer").mapNotNull { cue ->
            val start = cue.optString("startOffsetMs").toLongOrNull() ?: return@mapNotNull null
            val text = jsonText(cue.opt("cue")).trim(' ', '\n', '♪').takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            start to text
        }.sortedBy { it.first }
        cues.joinToString("\n") { (ms, text) ->
            val centiseconds = (ms % 1_000L) / 10L
            "[${ms / 60_000L}:${((ms / 1_000L) % 60L).toString().padStart(2, '0')}.${centiseconds.toString().padStart(2, '0')}]$text"
        }.takeIf(String::isNotBlank)
    }

    internal fun videoId(url: String): String? = runCatching {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val id = when {
            host == "youtu.be" -> uri.lastPathSegment.orEmpty()
            host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                uri.path == "/watch" -> uri.getQueryParameter("v").orEmpty()
                uri.path.orEmpty().startsWith("/shorts/") || uri.path.orEmpty().startsWith("/embed/") ->
                    uri.pathSegments.getOrNull(1).orEmpty()
                else -> ""
            }
            else -> ""
        }
        id.takeIf { idPattern.matches(it) }
    }.getOrNull()

    private fun post(endpoint: String, payload: JSONObject): JSONObject {
        val locale = Locale.getDefault()
        val version = currentClientVersion()
        val requestBody = JSONObject()
            .put("context", JSONObject()
                .put("client", JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", version)
                    .put("hl", locale.language.ifBlank { "en" })
                    .put("gl", locale.country.ifBlank { "IN" }))
                .put("user", JSONObject().put("lockedSafetyMode", false))
                .put("request", JSONObject().put("useSsl", true)))
        val keys = payload.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            requestBody.put(key, payload.get(key))
        }
        val connection = (URL("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("X-Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
            setRequestProperty("X-YouTube-Client-Name", "67")
            setRequestProperty("X-YouTube-Client-Version", version)
            setRequestProperty("Accept-Language", locale.toLanguageTag())
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
        }
        try {
            connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "YouTube Music lyrics unavailable ($status)." }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun currentClientVersion(): String {
        val now = System.currentTimeMillis()
        if (now - clientVersionCachedAt < TimeUnit.HOURS.toMillis(12)) return cachedClientVersion
        synchronized(this) {
            val checkedAt = System.currentTimeMillis()
            if (checkedAt - clientVersionCachedAt < TimeUnit.HOURS.toMillis(12)) return cachedClientVersion
            val connection: HttpURLConnection? = runCatching { (URL("https://music.youtube.com/").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2_500
                readTimeout = 3_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            } }.getOrNull()
            val discovered = try {
                val conn = connection ?: return cachedClientVersion.also { clientVersionCachedAt = checkedAt }
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.bufferedReader().use { reader ->
                    val html = reader.readText().take(2 * 1024 * 1024)
                    Regex("""\"INNERTUBE_CLIENT_VERSION\"\s*:\s*\"([^\"]+)\"""")
                        .find(html)?.groupValues?.getOrNull(1)
                }
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
            discovered?.takeIf { it.matches(Regex("\\d+\\.\\d{8}\\.\\d{2}\\.\\d{2}")) }?.let { cachedClientVersion = it }
            clientVersionCachedAt = checkedAt
            return cachedClientVersion
        }
    }

    private fun findObjects(root: Any?, name: String): List<JSONObject> = buildList {
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.optJSONObject(name)?.let(::add)
                    val keys = value.keys()
                    while (keys.hasNext()) visit(value.opt(keys.next()))
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }
        visit(root)
    }

    private fun findString(root: Any?, key: String): String? {
        when (root) {
            is JSONObject -> {
                root.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { return it }
                val keys = root.keys()
                while (keys.hasNext()) findString(root.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (index in 0 until root.length()) findString(root.opt(index), key)?.let { return it }
        }
        return null
    }

    private fun jsonText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("") { jsonText(value.opt(it)) }
        is JSONObject -> when {
            value.has("text") -> jsonText(value.opt("text"))
            value.has("simpleText") -> jsonText(value.opt("simpleText"))
            value.has("runs") -> jsonText(value.opt("runs"))
            else -> value.keys().asSequence().map { jsonText(value.opt(it)) }.joinToString("")
        }
        else -> ""
    }
}
