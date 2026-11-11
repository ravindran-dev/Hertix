package dev.ravindran.ythub.mobile

import android.icu.text.Transliterator
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.StringReader
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Strictly matched, multi-provider lyrics lookup with local positive-result caching. */
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

    private data class ProviderAnswer(val lyrics: String?, val failure: Throwable? = null)

    suspend fun find(track: LocalTrack, forceLookup: Boolean = false): String? = withContext(Dispatchers.IO) {
        val originalTitle = track.title.trim()
        val originalArtist = track.artist.trim()
        require(originalTitle.isNotEmpty()) { "Track title is needed to search for lyrics." }

        val key = listOf(track.sourceVideoUrl.orEmpty(), originalTitle, originalArtist, track.album, track.durationMs.toString())
            .joinToString("\u001f").lowercase(Locale.ROOT)
        if (forceLookup) entries.remove(key)
        else entries[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withContext it.lyrics }

        val title = stripDecorations(originalTitle)
        // YouTube downloads may expose the uploader or label as the artist.
        // Avoid rejecting a catalog match against a publisher/channel name.
        val artist = cleanArtist(originalArtist).takeUnless(::looksLikePublisher).orEmpty()
        val duration = track.durationMs.takeIf { it in 1_000L..3_600_000L }?.let { (it + 500L) / 1_000L }
        val videoUrl = track.sourceVideoUrl.orEmpty().ifBlank {
            track.downloadId?.let { id -> DownloadStore.items.value.firstOrNull { it.id == id }?.sourceVideoUrl(track.uri) }.orEmpty()
        }
        val latinTitle = transliterateForSearch(title).trim().takeIf { it.isNotBlank() && it != title }
        val latinArtist = transliterateForSearch(artist).trim().takeIf { it.isNotBlank() && it != artist }

        // Start independent exact-video and catalog providers together. Results are
        // still considered in a stable order, preferring timed lyrics over plain text.
        coroutineScope {
            suspend fun provider(block: suspend () -> String?): ProviderAnswer = try {
                ProviderAnswer(block())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                ProviderAnswer(null, failure)
            }

            val exactVideo = videoUrl.takeIf(String::isNotBlank)
            val jobs = mutableListOf<Pair<String, Deferred<ProviderAnswer>>>()
            fun launch(name: String, block: suspend () -> String?) {
                jobs += name to async(Dispatchers.IO) { provider(block) }
            }

            if (exactVideo != null) {
                launch("video catalog") { findSimpMusicLyrics(track, title, artist, duration) }
                launch("YouTube Music") { YouTubeInnertubeLyrics.lyrics(exactVideo) }
            }
            launch("YouTube Music search") { findYouTubeMusicLyrics(title, artist, duration) }
            launch("BiniLyrics") {
                findBiniLyrics(title, artist, duration, track.album)
                    ?: latinTitle?.let { findBiniLyrics(it, latinArtist ?: artist, duration, track.album) }
            }
            launch("BetterLyrics") {
                findBetterLyrics(title, artist, duration, track.album)
                    ?: latinTitle?.let { findBetterLyrics(it, latinArtist ?: artist, duration, track.album) }
            }
            launch("QQ lyrics") {
                findPortatoLyrics(title, artist, duration, track.album)
                    ?: latinTitle?.let { findPortatoLyrics(it, latinArtist ?: artist, duration, track.album) }
            }
            launch("LyricsPlus") {
                findLyricsPlus(title, artist, duration, track.album)
                    ?: latinTitle?.let { findLyricsPlus(it, latinArtist ?: artist, duration, track.album) }
            }
            launch("Unison") {
                findUnisonLyrics(title, artist, duration, track.album)
                    ?: latinTitle?.let { findUnisonLyrics(it, latinArtist ?: artist, duration, track.album) }
            }
            launch("KuGou") {
                findKuGouLyrics(title, artist, duration, track.album)
                    ?: latinTitle?.let { findKuGouLyrics(it, latinArtist ?: artist, duration, track.album) }
            }
            launch("LRCLIB") { findLrcLibLyrics(originalTitle, title, artist, duration, track.album) }
            if (exactVideo != null) launch("YouTube captions") { YouTubeInnertubeLyrics.transcript(exactVideo) }

            var plainFallback: String? = null
            var providerFailure: Throwable? = null
            try {
                for ((_, job) in jobs) {
                    val answer = job.await()
                    if (answer.failure != null) providerFailure = answer.failure
                    val candidate = answer.lyrics?.takeIf(String::isNotBlank) ?: continue
                    if (hasSyncedTiming(candidate)) return@coroutineScope cache(key, candidate)
                    if (plainFallback == null) plainFallback = candidate
                }
                plainFallback?.let { return@coroutineScope cache(key, it) }
                providerFailure?.let { throw IllegalStateException("Lyrics providers could not be reached. Try again later.", it) }
                cache(key, null)
            } finally {
                jobs.forEach { it.second.cancel() }
            }
        }
    }

    private suspend fun findLrcLibLyrics(originalTitle: String, title: String, artist: String, duration: Long?, album: String): String? {
        val exactParams = linkedMapOf("track_name" to originalTitle)
        artist.takeIf(::knownArtist)?.let { exactParams["artist_name"] = it }
        album.takeIf { it.isNotBlank() && !it.equals("Unknown album", true) }?.let { exactParams["album_name"] = it }
        duration?.let { exactParams["duration"] = it.toString() }
        if (knownArtist(artist)) {
            val exact = getRecord("$baseUrl/get", exactParams)
            if (exact is LookupResult.Found) exact.record.lyrics?.let { return it }
        }
        val searchParams = linkedMapOf("track_name" to title)
        artist.takeIf(::knownArtist)?.let { searchParams["artist_name"] = it }
        val best = selectBest(searchRecords(searchParams), title, artist, duration)
        best?.lyrics?.let { return it }
        if (knownArtist(artist) && duration != null) {
            val titleOnly = searchRecords(linkedMapOf("track_name" to title))
            selectBest(titleOnly, title, "", duration)?.lyrics?.let { return it }
        }
        if (title != originalTitle && knownArtist(artist)) {
            val alternate = getRecord("$baseUrl/get", linkedMapOf("track_name" to title, "artist_name" to artist))
            if (alternate is LookupResult.Found && selectBest(listOf(alternate.record), title, artist, duration) != null) return alternate.record.lyrics
        }
        return null
    }

    private suspend fun findYouTubeMusicSources(title: String, artist: String, duration: Long?): List<YouTubeResult> {
        val cleanTitle = stripDecorations(title).take(100)
        if (cleanTitle.length < 2) return emptyList()
        val cleanArtist = artist.takeIf(::knownArtist).orEmpty().take(80)
        // Innertube can index regional recordings under their Latin transliteration.
        // Search both scripts, then validate every candidate before requesting its tab.
        val latinTitle = transliterateForSearch(cleanTitle).trim()
        val latinArtist = transliterateForSearch(cleanArtist).trim()
        val primaryQueries = listOf("$cleanTitle $cleanArtist".trim(), "$latinTitle $latinArtist".trim())
            .filter(String::isNotBlank).distinct()
        val titleTokens = tokens(latinTitle)
        val artistTokens = tokens(latinArtist)
        val candidates = linkedMapOf<String, Pair<YouTubeResult, Double>>()
        suspend fun collect(query: String) {
            val results = runCatching { searchYouTube(query, "songs") }.getOrNull().orEmpty()
            val ranked = results.asSequence()
                .filter { it.type == "video" }
                .mapNotNull { result ->
                    val candidateTitle = tokens(transliterateForSearch(stripDecorations(result.title)))
                    val titleScore = tokenScore(titleTokens, candidateTitle)
                    val candidateArtist = tokens(transliterateForSearch(result.channel))
                    val artistRecall = if (artistTokens.isEmpty()) 0.0 else
                        artistTokens.intersect(candidateArtist).size.toDouble() / artistTokens.size
                    val delta = if (duration != null && result.durationSeconds > 0)
                        kotlin.math.abs(result.durationSeconds - duration) else Long.MAX_VALUE
                    val titleThreshold = if (artistTokens.isEmpty()) .92 else .72
                    if (titleScore < titleThreshold || (artistTokens.isNotEmpty() && artistRecall < .34) ||
                        (duration != null && result.durationSeconds > 0 && delta > 45)) null
                    else {
                        val durationBonus = if (delta != Long.MAX_VALUE) (1.0 - delta.coerceAtMost(45) / 45.0) * .12 else 0.0
                        result to (titleScore * .72 + artistRecall * .20 + durationBonus)
                    }
                }
                .sortedByDescending { it.second }
                .toList()
            for ((result, score) in ranked.take(4)) {
                val existing = candidates[result.id]
                if (existing == null || score > existing.second) candidates[result.id] = result to score
            }
        }
        for (query in primaryQueries) collect(query)
        if (candidates.isEmpty()) {
            listOf(cleanTitle, latinTitle).filter(String::isNotBlank).distinct().forEach { collect(it) }
        }
        return candidates.values.sortedByDescending { it.second }.take(3).map { it.first }
    }

    private suspend fun findYouTubeMusicLyrics(title: String, artist: String, duration: Long?): String? = coroutineScope {
        val sources = findYouTubeMusicSources(title, artist, duration)
        val pending = sources.map { source -> source to async(Dispatchers.IO) { YouTubeInnertubeLyrics.lyrics(source.url) } }
        var plain: String? = null
        try {
            for ((_, lyrics) in pending) {
                val value = lyrics.await()?.takeIf(String::isNotBlank) ?: continue
                if (hasSyncedTiming(value)) return@coroutineScope value
                if (plain == null) plain = value
            }
            if (plain != null) return@coroutineScope plain
            val captions = sources.map { source -> source to async(Dispatchers.IO) { YouTubeInnertubeLyrics.transcript(source.url) } }
            try {
                for ((_, transcript) in captions) transcript.await()?.takeIf { hasSyncedTiming(it) }?.let { return@coroutineScope it }
            } finally {
                captions.forEach { it.second.cancel() }
            }
            null
        } finally {
            pending.forEach { it.second.cancel() }
        }
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
            val durationAccepted = requestedDuration == null || durationDelta <= 45.0 || durationDelta.isInfinite()
            if (!titleAccepted || !artistAccepted || !durationAccepted) return@mapNotNull null
            val durationBonus = if (durationDelta.isFinite()) (1.0 - durationDelta.coerceAtMost(30.0) / 30.0) * .12 else 0.0
            Scored(record, titleScore * .65 + artistScore * .35 + durationBonus, titleScore, artistScore, durationDelta)
        }.sortedByDescending { it.score }

        val top = candidates.firstOrNull() ?: return null
        if (artistTokens.isEmpty() && (top.titleScore < .98 || (requestedDuration != null && top.durationDelta > 45.0))) return null
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

    private fun hasSyncedTiming(lyrics: String) = parseLyrics(lyrics).isNotEmpty()

    private fun tokens(value: String): Set<String> = normalize(transliterateForSearch(value))
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() && it !in removableTitleWords && it !in setOf("feat", "featuring", "ft") }
        .toSet()

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val folded = StringBuilder(decomposed.length)
        var previousScript = Character.UnicodeScript.COMMON
        var index = 0
        while (index < decomposed.length) {
            val codePoint = Character.codePointAt(decomposed, index)
            val type = Character.getType(codePoint)
            val mark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
            // Remove accent marks only from Latin text. Tamil and other Indic marks
            // change the actual letters and must remain intact for catalog matching.
            if (!mark || previousScript != Character.UnicodeScript.LATIN) folded.appendCodePoint(codePoint)
            if (!mark) previousScript = Character.UnicodeScript.of(codePoint)
            index += Character.charCount(codePoint)
        }
        return folded.toString()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }

    private fun stripDecorations(value: String): String = value
        .replace(Regex("\\([^)]*(official|video|audio|lyrics|visualizer|4k|hd|remaster)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\[[^]]*(official|video|audio|lyrics|visualizer|4k|hd|remaster)[^]]*]", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("[\\[(](tamil|telugu|hindi|malayalam|kannada|english)[\\]) ]", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\b(lyrical|lyric video|lyrics?|official|video|audio)\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun cleanArtist(value: String): String = value
        .replace(Regex("\\s*-\\s*topic$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*\\|.*$"), "")
        .trim()

    private fun knownArtist(value: String) = value.isNotBlank() &&
        !value.equals("Unknown artist", true) && !value.equals("Various Artists", true) && value != "null"

    private fun looksLikePublisher(value: String): Boolean =
        Regex("(?i)\\b(music|records|studios?|entertainment|films?|label|official|channel|network|productions?)\\b").containsMatchIn(value) ||
            value.split(Regex("[,|·]")).count { it.isNotBlank() } > 2

    private suspend fun findSimpMusicLyrics(track: LocalTrack, title: String, artist: String, duration: Long?): String? = withContext(Dispatchers.IO) {
        val sourceUrl = track.sourceVideoUrl.orEmpty().ifBlank {
            track.downloadId?.let { id -> DownloadStore.items.value.firstOrNull { it.id == id }?.sourceVideoUrl(track.uri) }.orEmpty()
        }
        val videoId = YouTubeInnertubeLyrics.videoId(sourceUrl) ?: return@withContext null
        val (status, body) = request("https://api-lyrics.simpmusic.org/v1/$videoId", emptyMap())
        if (status != HttpURLConnection.HTTP_OK || body.length > MAX_PROVIDER_BODY) return@withContext null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        if (!root.optBoolean("success", true)) return@withContext null
        val data = root.optJSONArray("data") ?: return@withContext null
        val candidates = (0 until data.length()).mapNotNull { index ->
            val hit = runCatching { data.getJSONObject(index) }.getOrNull() ?: return@mapNotNull null
            // SimpMusic is keyed by the exact video ID; its documented payload
            // contains duration + richSyncLyrics/syncedLyrics/plainLyrics (no
            // per-result title or artist). Do not reject that valid response for
            // missing metadata, which previously made regional tracks look empty.
            val candidateTitle = hit.optString("songTitle").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val candidateArtist = hit.optString("artistName").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val rich = hit.optString("richSyncLyrics").cleanLyrics()?.let(::decodeHtmlEntities)
                ?.replace(Regex("<\\d{1,3}:\\d{2}[.:]\\d{2,3}>"), "")
            val synced = hit.optString("syncedLyrics").cleanLyrics()
            val plain = hit.optString("plainLyrics").cleanLyrics() ?: hit.optString("plainLyric").cleanLyrics()
            val rawLyrics = rich ?: synced ?: plain ?: hit.optString("lyrics").cleanLyrics() ?: return@mapNotNull null
            val lyrics = when {
                rawLyrics.contains("<tt", true) -> ttmlToLrc(rawLyrics)
                parseLyrics(rawLyrics).isNotEmpty() -> rawLyrics
                rawLyrics.contains("<") && rawLyrics.contains("]") -> decodeRichSync(rawLyrics)
                else -> rawLyrics.takeIf { it.isNotBlank() }
            }?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val seconds = hit.optDouble("durationSeconds", hit.optDouble("duration", 0.0)).takeIf { it.isFinite() && it > 0 } ?: 0.0
            val record = Record(hit.optString("id").toLongOrNull() ?: index.toLong(), candidateTitle, candidateArtist, seconds, false, lyrics, lyrics)
            val titleScore = if (candidateTitle.isBlank()) 1.0 else tokenScore(tokens(title), tokens(candidateTitle))
            val artistScore = if (!knownArtist(artist) || candidateArtist.isBlank()) 0.0 else tokenScore(tokens(artist), tokens(candidateArtist))
            val delta = if (duration != null && seconds > 0) kotlin.math.abs(seconds - duration) else Double.POSITIVE_INFINITY
            // The API is keyed by this exact YouTube video ID; metadata scoring only
            // chooses between lyric variants returned for that video.
            Triple(record, titleScore * .7 + artistScore * .2 + if (delta.isFinite()) (1.0 - delta.coerceAtMost(30.0) / 30.0) * .1 else 0.0, delta)
        }.sortedByDescending { it.second }
        val best = candidates.firstOrNull() ?: return@withContext null
        if (best.first.title.isNotBlank() && tokens(best.first.title).isNotEmpty() && best.second < .25) return@withContext null
        if (best.first.title.isNotBlank() && candidates.getOrNull(1)?.let { best.second - it.second < .04 } == true) return@withContext null
        best.first.lyrics
    }

    /** Make native-script titles searchable in catalogs that index Latin spellings. */
    private fun transliterateForSearch(value: String): String {
        if (value.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Tanglish.convert(value)
        val scripts = listOf(
            0x0B80..0x0BFF to "Tamil-Latin", 0x0C00..0x0C7F to "Telugu-Latin",
            0x0C80..0x0CFF to "Kannada-Latin", 0x0D00..0x0D7F to "Malayalam-Latin",
            0x0980..0x09FF to "Bengali-Latin", 0x0A80..0x0AFF to "Gujarati-Latin",
            0x0A00..0x0A7F to "Gurmukhi-Latin", 0x0900..0x097F to "Devanagari-Latin",
            0x0B00..0x0B7F to "Oriya-Latin"
        )
        var result = value
        for ((range, id) in scripts) if (result.any { it.code in range }) {
            result = runCatching { Transliterator.getInstance("$id; Latin-ASCII").transliterate(result) }.getOrDefault(result)
        }
        return result
    }

    private fun decodeRichSync(raw: String): String? = runCatching {
        val decoded = runCatching { String(Base64.getDecoder().decode(raw), Charsets.UTF_8) }.getOrDefault(raw)
        decodeHtmlEntities(decoded).replace("\\\\n", "\n").replace("\\n", "\n")
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun decodeHtmlEntities(value: String): String = value
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    private suspend fun findKuGouLyrics(title: String, artist: String, duration: Long?, album: String): String? = withContext(Dispatchers.IO) {
        val keyword = buildString {
            append(title.replace(Regex("[（(].*?[）)]"), "").trim())
            if (knownArtist(artist)) append(" - ").append(artist)
            if (album.isNotBlank() && !album.equals("Unknown album", true)) append(' ').append(album)
        }
        val seconds = duration?.toInt() ?: -1
        val songResponse = request("https://mobileservice.kugou.com/api/v3/search/song", linkedMapOf(
            "version" to "9108", "plat" to "0", "pagesize" to "8", "showtype" to "0", "keyword" to keyword
        ))
        if (songResponse.first !in 200..299) return@withContext null
        val songHits = runCatching { JSONObject(songResponse.second).optJSONObject("data")?.optJSONArray("info") }.getOrNull()
        val hashes = (0 until (songHits?.length() ?: 0)).mapNotNull { index ->
            val item = songHits?.optJSONObject(index) ?: return@mapNotNull null
            val hash = item.optString("hash").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val songDuration = item.optInt("duration", -1)
            if (seconds > 0 && songDuration > 0 && kotlin.math.abs(songDuration - seconds) > 35) null else hash
        }

        fun lyricCandidates(params: Map<String, String>): List<JSONObject> {
            val response = request("https://lyrics.kugou.com/search", params + mapOf("ver" to "1", "man" to "yes", "client" to "pc"))
            if (response.first !in 200..299) return emptyList()
            val root = runCatching { JSONObject(response.second) }.getOrNull() ?: return emptyList()
            val array = root.optJSONArray("candidates") ?: return emptyList()
            return (0 until array.length()).mapNotNull(array::optJSONObject)
        }
        val candidates = hashes.firstOrNull()?.let { hash -> lyricCandidates(mapOf("hash" to hash)).takeIf { it.isNotEmpty() } }
            ?: lyricCandidates(buildMap {
                put("keyword", keyword)
                if (seconds > 0) put("duration", (seconds * 1000).toString())
            })
        val selected = candidates.mapNotNull { candidate ->
            val song = candidate.optString("song")
            val candidateArtist = candidate.optString("artist").ifBlank { candidate.optString("singer") }
            val titleScore = tokenScore(tokens(title), tokens(song))
            val artistScore = if (knownArtist(artist)) tokenScore(tokens(artist), tokens(candidateArtist)) else 0.0
            val score = if (knownArtist(artist)) titleScore * .65 + artistScore * .35 else titleScore
            if (score < if (knownArtist(artist)) .55 else .82) null else candidate to score
        }.maxByOrNull { it.second }?.first ?: return@withContext null
        val id = selected.optString("id").takeIf(String::isNotBlank) ?: return@withContext null
        val accessKey = selected.optString("accesskey").takeIf(String::isNotBlank) ?: return@withContext null
        val downloaded = request("https://lyrics.kugou.com/download", mapOf(
            "fmt" to "lrc", "charset" to "utf8", "client" to "pc", "ver" to "1", "id" to id, "accesskey" to accessKey
        ))
        if (downloaded.first !in 200..299 || downloaded.second.length > MAX_PROVIDER_BODY) return@withContext null
        val encoded = runCatching { JSONObject(downloaded.second).optString("content") }.getOrNull().orEmpty()
        val lrc = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull().orEmpty()
        val timed = lrc.lineSequence().filter { Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}].*").matches(it) }.toList()
        timed.joinToString("\n").takeIf { parseLyrics(it).isNotEmpty() }
    }

    private suspend fun findBiniLyrics(title: String, artist: String, duration: Long?, album: String): String? = withContext(Dispatchers.IO) {
        val params = linkedMapOf("track" to title)
        artist.takeIf(::knownArtist)?.let { params["artist"] = it }
        album.takeIf { it.isNotBlank() && !it.equals("Unknown album", true) }?.let { params["album"] = it }
        duration?.let { params["duration"] = it.toString() }
        val (status, body) = request("https://lyrics-api.binimum.org/", params)
        if (status != HttpURLConnection.HTTP_OK || body.length > MAX_PROVIDER_BODY) return@withContext null
        val hits = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return@withContext null
        val requestedTitleTokens = tokens(title)
        val best = (0 until hits.length()).mapNotNull { index ->
            val hit = runCatching { hits.getJSONObject(index) }.getOrNull() ?: return@mapNotNull null
            val candidateTitle = hit.optString("track_name").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val candidateArtist = hit.optString("artist_name").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val titleScore = tokenScore(tokens(title), tokens(candidateTitle))
            val candidateTitleTokens = tokens(candidateTitle)
            val titleRecall = if (requestedTitleTokens.isEmpty()) 0.0 else requestedTitleTokens.intersect(candidateTitleTokens).size.toDouble() / requestedTitleTokens.size
            val artistScore = if (knownArtist(artist)) tokenScore(tokens(artist), tokens(candidateArtist)) else 0.0
            val candidateDuration = hit.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0 }
            val durationDelta = if (duration != null && candidateDuration != null) kotlin.math.abs(candidateDuration - duration) else Double.POSITIVE_INFINITY
            val exactTitle = titleRecall >= .99
            val durationAccepted = duration == null || !durationDelta.isFinite() || durationDelta <= 45.0 || (exactTitle && durationDelta <= 120.0)
            val exactEnough = if (knownArtist(artist)) titleScore >= .7 && artistScore >= .35 else exactTitle && durationAccepted
            if (!exactEnough || (knownArtist(artist) && durationDelta.isFinite() && durationDelta > 45.0)) return@mapNotNull null
            val url = hit.optString("lyricsUrl")
            val safeLyricsUrl = runCatching { URL(url) }.getOrNull()?.let { parsed ->
                parsed.protocol == "https" && parsed.port == -1 &&
                    ((parsed.host.equals("lrc.red", true) && parsed.path.startsWith("/s/") && parsed.path.endsWith(".ttml")) ||
                        (parsed.host.equals("lyrics-storage.binimum.org", true) && parsed.path.endsWith(".ttml")))
            } == true
            if (!safeLyricsUrl) return@mapNotNull null
            (hit to ((if (knownArtist(artist)) titleScore else titleRecall) * .65 + artistScore * .35 + if (durationDelta.isFinite()) (1.0 - durationDelta.coerceAtMost(30.0) / 30.0) * .12 else 0.0))
        }.sortedByDescending { it.second }
        val selected = best.firstOrNull() ?: return@withContext null
        if (best.getOrNull(1)?.let { selected.second - it.second < .06 } == true) return@withContext null
        val url = selected.first.optString("lyricsUrl")
        val (documentStatus, document) = request(url, emptyMap())
        if (documentStatus != HttpURLConnection.HTTP_OK) return@withContext null
        ttmlToLrc(document)
    }

    private suspend fun findBetterLyrics(title: String, artist: String, duration: Long?, album: String): String? = withContext(Dispatchers.IO) {
        if (!knownArtist(artist)) return@withContext null
        val params = linkedMapOf(
            "s" to title,
            "a" to artist,
            "al" to album.takeIf { it.isNotBlank() && !it.equals("Unknown album", true) }.orEmpty(),
            "d" to duration.toString()
        )
        if (duration == null) return@withContext null
        val endpoints = listOf("https://lyrics-api.boidu.dev/getLyrics", "https://api.betterlyrics.org/getLyrics")
        val response = endpoints.asSequence().map { endpoint -> runCatching { request(endpoint, params) }.getOrNull() }
            .firstOrNull { it?.first == HttpURLConnection.HTTP_OK } ?: return@withContext null
        val (status, body) = response
        if (status != HttpURLConnection.HTTP_OK || body.length > MAX_PROVIDER_BODY) return@withContext null
        val content = providerContent(body)?.decodeProviderEntities()?.trim()?.takeIf(String::isNotBlank) ?: return@withContext null
        when {
            content.contains("<tt", ignoreCase = true) -> ttmlToLrc(content)
            parseLyrics(content).isNotEmpty() -> content
            content.contains(Regex("(?i)lyrics? (not found|unavailable)|^error")) -> null
            else -> content
        }
    }

    private suspend fun findPortatoLyrics(title: String, artist: String, duration: Long?, album: String): String? = withContext(Dispatchers.IO) {
        if (!knownArtist(artist) || duration == null) return@withContext null
        val params = linkedMapOf("s" to title, "a" to artist, "d" to duration.toString())
        album.takeIf { it.isNotBlank() && !it.equals("Unknown album", true) }?.let { params["al"] = it }
        val (status, body) = request("https://lyrics-api.boidu.dev/qq/getLyrics", params)
        if (status != HttpURLConnection.HTTP_OK || body.length > MAX_PROVIDER_BODY) return@withContext null
        providerContent(body)?.decodeProviderEntities()?.let { content ->
            when {
                content.contains("<tt", true) -> ttmlToLrc(content)
                parseLyrics(content).isNotEmpty() -> content
                content.contains(Regex("(?i)lyrics? (not found|unavailable)|^error")) -> null
                else -> content.takeIf(String::isNotBlank)
            }
        }
    }

    private suspend fun findLyricsPlus(title: String, artist: String, duration: Long?, album: String): String? = coroutineScope {
        val seconds = duration ?: 0L
        val query = buildString {
            append("title=").append(URLEncoder.encode(title, Charsets.UTF_8.name()))
            append("&artist=").append(URLEncoder.encode(artist, Charsets.UTF_8.name()))
            if (seconds > 0) append("&duration=").append(seconds)
            if (album.isNotBlank() && !album.equals("Unknown album", true)) append("&album=").append(URLEncoder.encode(album, Charsets.UTF_8.name()))
        }
        val mirrors = listOf(
            "https://lyricsplus.prjktla.my.id", "https://lyricsplus.atomix.one",
            "https://lyricsplus.binimum.org", "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus-seven.vercel.app", "https://lyrics-plus-backend.vercel.app"
        )
        val pending = mirrors.map { host -> async(Dispatchers.IO) {
            runCatching { request("$host/v2/lyrics/get?$query", emptyMap()) }.getOrNull()
                ?.takeIf { it.first in 200..299 && it.second.length <= MAX_PROVIDER_BODY }
                ?.let { parseLyricsPlus(it.second) }
        } }
        try {
            for (job in pending) job.await()?.takeIf(String::isNotBlank)?.let { return@coroutineScope it }
            null
        } finally {
            pending.forEach { it.cancel() }
        }
    }

    private fun parseLyricsPlus(body: String): String? = runCatching {
        val root = JSONObject(body)
        val lines = root.optJSONArray("lyrics") ?: root.optJSONObject("data")?.optJSONArray("lyrics") ?: return null
        val output = mutableListOf<Pair<Long, String>>()
        for (index in 0 until lines.length()) {
            val item = lines.optJSONObject(index) ?: continue
            val start = item.optLong("time", -1L).takeIf { it >= 0 } ?: continue
            val syllables = item.optJSONArray("syllabus")
            val text = item.optString("text").takeIf { it.isNotBlank() && it != "null" }
                ?: syllables?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }.joinToString("") }
                ?: ""
            if (text.isNotBlank()) output += start to text.trim()
        }
        output.sortedBy { it.first }.joinToString("\n") { (ms, text) ->
            val cs = (ms % 1_000L) / 10L
            "[${ms / 60_000L}:${((ms / 1_000L) % 60L).toString().padStart(2, '0')}.${cs.toString().padStart(2, '0')}]$text"
        }.takeIf { parseLyrics(it).isNotEmpty() }
    }.getOrNull()

    private suspend fun findUnisonLyrics(title: String, artist: String, duration: Long?, album: String): String? = withContext(Dispatchers.IO) {
        val params = linkedMapOf("song" to title)
        artist.takeIf(::knownArtist)?.let { params["artist"] = it }
        duration?.let { params["duration"] = it.toString() }
        album.takeIf { it.isNotBlank() && !it.equals("Unknown album", true) }?.let { params["album"] = it }
        val (status, body) = request("https://unison.boidu.dev/lyrics", params)
        if (status != HttpURLConnection.HTTP_OK || body.length > MAX_PROVIDER_BODY) return@withContext null
        val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull() ?: return@withContext null
        if (data.optString("lyrics").isBlank()) return@withContext null
        val candidateTitle = data.optString("song")
        val candidateArtist = data.optString("artist")
        if (candidateTitle.isNotBlank() && tokenScore(tokens(title), tokens(candidateTitle)) < .68) return@withContext null
        if (knownArtist(artist) && candidateArtist.isNotBlank() && tokenScore(tokens(artist), tokens(candidateArtist)) < .3) return@withContext null
        val content = data.optString("lyrics").decodeProviderEntities()
        when {
            data.optString("format").equals("ttml", true) || content.contains("<tt", true) -> ttmlToLrc(content)
            parseLyrics(content).isNotEmpty() -> content
            data.optString("syncType").equals("plain", true) -> content
            else -> content
        }
    }

    private fun providerContent(raw: String): String? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        if (root.optBoolean("isError") || root.has("error") && !root.isNull("error")) return null
        val keys = listOf("ttml", "ttmlContent", "lyrics", "syncedLyrics", "plainLyrics", "lrc", "content", "text", "data", "result", "response")
        for (key in keys) {
            if (!root.has(key) || root.isNull(key)) continue
            val value = root.opt(key)
            when (value) {
                is String -> {
                    if (value.isNotBlank()) return providerContent(value) ?: value
                }
                is JSONObject -> providerContent(value.toString())?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun String.decodeProviderEntities(): String = replace("&lt;", "<", true).replace("&gt;", ">", true)
        .replace("&quot;", "\"", true).replace("&apos;", "'", true).replace("&#39;", "'", true).replace("&amp;", "&", true)

    /** Convert Apple TTML paragraphs to the player's existing timestamped LRC format. */
    private fun ttmlToLrc(document: String): String? = runCatching {
        if (document.length !in 1..MAX_PROVIDER_BODY) return null
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(StringReader(document))
        val output = mutableListOf<String>()
        var begin: Long? = null
        var text = StringBuilder()
        var inParagraph = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name.equals("p", true)) {
                    inParagraph = true
                    text = StringBuilder()
                    begin = ttmlTime(parser.getAttributeValue(null, "begin"))
                } else if (inParagraph && parser.name.equals("br", true)) text.append(' ')
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inParagraph) text.append(parser.text)
                XmlPullParser.END_TAG -> if (inParagraph && parser.name.equals("p", true)) {
                    val start = begin
                    val line = text.toString().replace(Regex("\\s+"), " ").trim()
                    if (start != null && line.isNotEmpty()) {
                        val centiseconds = (start % 1000L) / 10L
                        output += "[${start / 60_000L}:${((start / 1000L) % 60L).toString().padStart(2, '0')}.${centiseconds.toString().padStart(2, '0')}]$line"
                    }
                    inParagraph = false
                }
            }
        }
        output.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }.getOrNull()

    private fun ttmlTime(value: String?): Long? = runCatching {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (raw.endsWith("ms")) return raw.dropLast(2).toDouble().toLong()
        val parts = raw.removeSuffix("s").split(':')
        val seconds = when (parts.size) {
            1 -> parts[0].toDouble()
            2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
            3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
            else -> return null
        }
        (seconds * 1000).toLong()
    }.getOrNull()

    private const val MAX_PROVIDER_BODY = 2 * 1024 * 1024

    private fun request(endpoint: String, params: Map<String, String>): Pair<Int, String> {
        val query = params.entries.joinToString("&") { (name, value) ->
            "${URLEncoder.encode(name, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        val requestUrl = endpoint + if (query.isEmpty()) "" else "?$query"
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 5_000
            setRequestProperty("Accept", "application/json, application/ttml+xml, application/xml, text/plain;q=0.9, */*;q=0.5")
            setRequestProperty("User-Agent", "Hertix-Android/${BuildConfig.VERSION_NAME} (https://github.com/ravindran-dev/Hertix)")
        }
        try {
            val status = connection.responseCode
            if (status == 429 || status == HttpURLConnection.HTTP_UNAVAILABLE) {
                val waitSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
                throw IllegalStateException(if (waitSeconds != null) "Lyrics service is busy. Try again in $waitSeconds seconds." else "Lyrics service is busy. Try again later.")
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.append(buffer, 0, count)
                    require(output.length <= MAX_PROVIDER_BODY) { "Lyrics response is too large." }
                }
                output.toString()
            }.orEmpty()
            return status to body
        } finally {
            connection.disconnect()
        }
    }
}
