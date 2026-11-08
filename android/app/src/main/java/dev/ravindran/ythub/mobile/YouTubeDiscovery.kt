package dev.ravindran.ythub.mobile

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal data class YouTubeResult(val id: String, val type: String, val title: String, val channel: String, val thumbnail: String, val url: String)

/** BitChord-style YouTube Music WEB_REMIX discovery; this path uses no Data API key/quota. */
internal suspend fun searchYouTube(query: String, type: String = "all"): List<YouTubeResult> = withContext(Dispatchers.IO) {
    val cleanQuery = query.trim().replace(Regex("\\s+"), " ").take(100)
    require(cleanQuery.length >= 2) { "Enter at least 2 characters." }
    val filter = when (type) {
        "songs" -> "EgWKAQIIAWoKEAkQChAFEAMQBA=="
        "videos" -> "EgWKAQIQAWoKEAkQChAFEAMQBA=="
        "artists", "channels" -> "EgWKAQIgAWoKEAkQChAFEAMQBA=="
        "playlists" -> "EgWKAQIoAWoKEAkQChAFEAMQBA=="
        "all" -> null
        else -> error("Unsupported YouTube search type.")
    }
    val version = "1.20250101.01.00"
    val locale = Locale.getDefault()
    val language = locale.language.ifBlank { "en" }
    val region = locale.country.ifBlank { "IN" }
    val payload = JSONObject().put("context", JSONObject()
        .put("client", JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", version).put("hl", language).put("gl", region))
        .put("user", JSONObject().put("lockedSafetyMode", false))
        .put("request", JSONObject().put("useSsl", true)))
        .put("query", cleanQuery).apply { if (filter != null) put("params", filter) }
    val connection = (URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 8_000
        readTimeout = 10_000
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
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) error(if (status == 429) "YouTube Music search is temporarily busy. Try again shortly." else "YouTube Music search is temporarily unavailable ($status).")
        parseYouTubeMusicResults(JSONObject(responseText), type)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (failure.message?.startsWith("YouTube Music") == true) throw failure
        throw IllegalStateException("YouTube Music search failed. Check your connection and try again.", failure)
    } finally {
        connection.disconnect()
    }
}

private fun parseYouTubeMusicResults(root: JSONObject, requestedType: String): List<YouTubeResult> {
    val renderers = mutableListOf<JSONObject>()
    collectObjects(root, "musicResponsiveListItemRenderer", renderers)
    val seen = HashSet<String>()
    return renderers.mapNotNull { row ->
        val columns = row.optJSONArray("flexColumns") ?: JSONArray()
        val texts = (0 until columns.length()).map { i ->
            columns.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.let(::rendererText).orEmpty()
        }
        val title = texts.firstOrNull().orEmpty().trim()
        val videoId = findString(row, "videoId")
        val playlistId = findString(row, "playlistId")
        val browseId = findString(row, "browseId")
        val id = videoId.ifBlank { playlistId.ifBlank { browseId } }
        if (id.isBlank() || title.isBlank() || !seen.add(id)) return@mapNotNull null
        val resultType = when {
            videoId.isNotBlank() -> "video"
            playlistId.isNotBlank() -> "playlist"
            else -> "channel"
        }
        if (requestedType == "playlists" && resultType != "playlist") return@mapNotNull null
        if ((requestedType == "artists" || requestedType == "channels") && resultType != "channel") return@mapNotNull null
        val thumbs = findArray(row, "thumbnails")
        val thumbnail = (0 until thumbs.length()).mapNotNull { thumbs.optJSONObject(it)?.optString("url") }
            .lastOrNull { it.startsWith("http") }.orEmpty()
        val channel = texts.drop(1).filter(String::isNotBlank).joinToString(" · ")
        val url = when (resultType) {
            "playlist" -> "https://music.youtube.com/playlist?list=$id"
            "channel" -> "https://music.youtube.com/channel/$id"
            else -> "https://music.youtube.com/watch?v=$id"
        }
        YouTubeResult(id, resultType, title, channel, thumbnail, url)
    }
}

private fun rendererText(value: JSONObject): String {
    value.optString("simpleText").takeIf(String::isNotBlank)?.let { return it }
    val runs = value.optJSONArray("runs") ?: return ""
    return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
}

private fun collectObjects(root: Any?, key: String, output: MutableList<JSONObject>) {
    when (root) {
        is JSONObject -> {
            root.optJSONObject(key)?.let { output += it }
            val keys = root.keys()
            while (keys.hasNext()) collectObjects(root.opt(keys.next()), key, output)
        }
        is JSONArray -> for (index in 0 until root.length()) collectObjects(root.opt(index), key, output)
    }
}

private fun findString(root: Any?, key: String): String {
    when (root) {
        is JSONObject -> {
            root.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            val keys = root.keys()
            while (keys.hasNext()) findString(root.opt(keys.next()), key).takeIf(String::isNotBlank)?.let { return it }
        }
        is JSONArray -> for (index in 0 until root.length()) findString(root.opt(index), key).takeIf(String::isNotBlank)?.let { return it }
    }
    return ""
}

private fun findArray(root: Any?, key: String): JSONArray {
    when (root) {
        is JSONObject -> {
            root.optJSONArray(key)?.let { return it }
            val keys = root.keys()
            while (keys.hasNext()) findArray(root.opt(keys.next()), key).takeIf { it.length() > 0 }?.let { return it }
        }
        is JSONArray -> for (index in 0 until root.length()) findArray(root.opt(index), key).takeIf { it.length() > 0 }?.let { return it }
    }
    return JSONArray()
}

internal fun openYouTubeLink(context: android.content.Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
