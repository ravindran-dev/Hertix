package dev.ravindran.ythub.mobile

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class YTHubApplication : Application() {
    override fun onCreate() { super.onCreate(); DownloadStore.init(this); MusicRepository.init(this); MusicPlayback.init(this); CloudAccount.init(this); CloudBackupPolicy.init(this) }
}

object DownloadStore {
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val engineMutex = Mutex()
    private val active = mutableSetOf<String>()
    private val gson = Gson()
    private val _prefs = MutableStateFlow(Preferences())
    val prefs = _prefs.asStateFlow()
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items = _items.asStateFlow()
    private val _engine = MutableStateFlow("Checking local media engine…")
    val engine = _engine.asStateFlow()
    private val _sharedUrl = MutableStateFlow("")
    val sharedUrl = _sharedUrl.asStateFlow()
    @Volatile private var ready = false
    @Volatile private var runtimeVersion = ""
    private var updatingEngine = false
    private var preparingQueue = false
    private val enginePrefs get() = context.getSharedPreferences("ythub-engine", Context.MODE_PRIVATE)
    private val historyFile get() = File(context.filesDir, "downloads.json")
    val workRoot get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "media").apply { mkdirs() }
    fun init(ctx: Context) {
        context = ctx.applicationContext
        val stored = context.getSharedPreferences("ythub", Context.MODE_PRIVATE).getString("settings", null)
        _prefs.value = runCatching { gson.fromJson(stored, Preferences::class.java) }.getOrNull() ?: Preferences()
        _items.value = runCatching {
            gson.fromJson<List<DownloadItem>>(historyFile.readText(), object : TypeToken<List<DownloadItem>>() {}.type)
        }.getOrNull()?.map { if (it.working || it.state == "Queued") it.copy(state = "Interrupted", line = "Tap resume to continue after restart") else it } ?: emptyList()
        scope.launch { initializeEngine() }
    }
    fun share(url: String) { _sharedUrl.value = url }
    fun consumeShare() { _sharedUrl.value = "" }
    fun savePrefs(value: Preferences) {
        _prefs.value = value.copy(concurrency = value.concurrency.coerceIn(1, 3), retries = value.retries.coerceIn(0, 10))
        context.getSharedPreferences("ythub", Context.MODE_PRIVATE).edit().putString("settings", gson.toJson(_prefs.value)).apply()
        schedule()
    }
    private fun persist() {
        val temporary = File(context.filesDir, "downloads.json.tmp")
        temporary.outputStream().use { stream -> stream.write(gson.toJson(_items.value).toByteArray()); stream.fd.sync() }
        check(temporary.renameTo(historyFile)) { "Could not save download history" }
    }
    private fun change(id: String, save: Boolean = true, operation: (DownloadItem) -> DownloadItem) {
        synchronized(lock) {
            _items.value = _items.value.map { if (it.id == id) operation(it) else it }
            if (save) persist()
        }
    }
    private suspend fun initializeEngine() = engineMutex.withLock {
        if (ready) return@withLock
        try {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            runtimeVersion = YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList<String>()).addOption("--version")).out.trim().removePrefix("yt-dlp ")
            ready = true
            _engine.value = "Local media engine ready"
        } catch (e: Exception) { _engine.value = "Engine unavailable: ${e.message}" }
    }
    suspend fun updateEngine(force: Boolean = true): String = withContext(Dispatchers.IO) {
        initializeEngine()
        check(ready) { _engine.value }
        engineMutex.withLock {
            if (!force && !engineCheckDue()) return@withLock "yt-dlp ${engineVersion()}"
            synchronized(lock) {
                check(active.isEmpty() && !updatingEngine) { "Wait for active downloads before updating the engine." }
                updatingEngine = true
            }
            try {
                _engine.value = "Updating local media engine…"
                YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                runtimeVersion = YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList<String>()).addOption("--version")).out.trim().removePrefix("yt-dlp ")
                "yt-dlp $runtimeVersion"
            } finally {
                // Back off failed network checks too; a queue must not get stuck retrying an update.
                enginePrefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                synchronized(lock) { updatingEngine = false }
                _engine.value = "Local media engine ready"
                schedule()
            }
        }
    }
    fun engineVersion() = runtimeVersion.ifBlank { "Checking installed version…" }
    private fun engineCheckDue() = engineUpdateDue(System.currentTimeMillis(), enginePrefs.getLong("last_check", 0))
    private suspend fun refreshEngineIfDue() {
        if (!engineCheckDue()) return
        try { updateEngine(force = false) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the installed engine usable when the update service is offline. */ }
    }
    private fun request(url: String, options: Preferences): YoutubeDLRequest {
        val request = YoutubeDLRequest(url).addOption("--ignore-config")
            .addOption("--socket-timeout", 30).addOption("--retries", options.retries)
        if (options.proxy.isNotBlank()) request.addOption("--proxy", options.proxy)
        if (options.cookiesPath.isNotBlank()) request.addOption("--cookies", options.cookiesPath)
        return request
    }
    suspend fun analyze(raw: String, playlist: Boolean): MediaInfo = withContext(Dispatchers.IO) {
        val url = validYouTubeUrl(raw)
        initializeEngine()
        check(ready) { _engine.value }
        if (synchronized(lock) { active.isEmpty() && !preparingQueue }) refreshEngineIfDue()
        val request = request(url, prefs.value).addOption("--dump-single-json").addOption("--skip-download")
        if (playlist) request.addOption("--flat-playlist") else request.addOption("--no-playlist")
        val result = engineMutex.withLock { YoutubeDL.getInstance().execute(request, "analysis-${UUID.randomUUID()}") }
        parseMedia(JSONObject(result.out.trim()), url)
    }
    private fun parseMedia(json: JSONObject, source: String): MediaInfo {
        val entries = json.optJSONArray("entries")
        val children = if (entries != null) (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString("id")
            val url = entry.optString("webpage_url").ifBlank { entry.optString("url") }.let { if (it.startsWith("https://")) it else "https://www.youtube.com/watch?v=$id" }
            parseMedia(entry, url)
        } else emptyList()
        val formats = json.optJSONArray("formats")
        val heights = if (formats == null) emptyList() else (0 until formats.length()).mapNotNull { formats.optJSONObject(it)?.optInt("height")?.takeIf { h -> h > 0 } }.distinct().sortedDescending()
        val thumbnails = json.optJSONArray("thumbnails")
        val thumbnail = json.optString("thumbnail").ifBlank { thumbnails?.optJSONObject((thumbnails.length() - 1).coerceAtLeast(0))?.optString("url") ?: children.firstOrNull()?.thumbnail.orEmpty() }
        return MediaInfo(source, json.optString("title", "YouTube media"), json.optString("uploader").ifBlank { json.optString("channel") }, thumbnail,
            json.optLong("duration", 0), json.optLong("view_count", 0), json.optString("upload_date"), entries != null, children, heights,
            if (entries != null) json.optInt("playlist_count", children.size).coerceAtLeast(children.size) else 1)
    }
    fun enqueue(media: MediaInfo, audio: Boolean, quality: String, createLibraryPlaylist: Boolean? = null, libraryPlaylistName: String? = null): String {
        require(createLibraryPlaylist != true || (audio && media.playlist && !libraryPlaylistName.isNullOrBlank())) { "Enter a name for the audio playlist" }
        check(ready) { _engine.value }
        val id = UUID.randomUUID().toString()
        val item = DownloadItem(id, media, audio, quality, state = if (prefs.value.autoStart) "Queued" else "Paused", options = prefs.value, createLibraryPlaylist = createLibraryPlaylist, libraryPlaylistName = libraryPlaylistName?.trim())
        synchronized(lock) {
            require(_items.value.count { !it.terminal } < 100) { "Queue is full. Complete or remove an existing task first." }
            _items.value = listOf(item) + _items.value
            persist()
        }
        ensureService()
        return id
    }
    private fun ensureService() {
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }
    fun enqueueCloud(record: com.google.gson.JsonObject, play: Boolean): String {
        val owner=CloudAccount.state.value.userId ?: error("Sign in first")
        val objectId=record.get("id").asString
        val id="cloud-$owner-$objectId"
        synchronized(lock) {
            val existing=_items.value.find {it.id==id}
            if(existing!=null && !existing.terminal) return id
            val item=DownloadItem(id,MediaInfo("cloud://$objectId",record.get("display_name").asString),true,"original",cloudRecord=record.toString(),accountId=owner,playWhenReady=play,options=prefs.value)
            _items.value=_items.value.filterNot {it.id==id}+item;persist()
        }
        ensureService();schedule();return id
    }
    var cloudExternalStorage: Boolean
        get()=context.getSharedPreferences("cloud-download-prefs",0).getBoolean("externalStorage",false)
        set(value){context.getSharedPreferences("cloud-download-prefs",0).edit().putBoolean("externalStorage",value).apply()}
    var cloudStorageReserveMb: Int
        get()=context.getSharedPreferences("cloud-download-prefs",0).getInt("storageReserve",64).coerceIn(16,512)
        set(value){context.getSharedPreferences("cloud-download-prefs",0).edit().putInt("storageReserve",value.coerceIn(16,512)).apply()}
    var cloudWifiOnly: Boolean
        get()=context.getSharedPreferences("cloud-download-prefs",0).getBoolean("wifiOnly",false)
        set(value){context.getSharedPreferences("cloud-download-prefs",0).edit().putBoolean("wifiOnly",value).apply();schedule()}
    var cloudConcurrency: Int
        get()=context.getSharedPreferences("cloud-download-prefs",0).getInt("concurrency",1).coerceIn(1,3)
        set(value){context.getSharedPreferences("cloud-download-prefs",0).edit().putInt("concurrency",value.coerceIn(1,3)).apply();schedule()}
    private fun cloudNetworkAllowed():Boolean {
        if(!cloudWifiOnly)return true
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true
    }
    var cloudAutoRetry: Boolean
        get()=context.getSharedPreferences("cloud-download-prefs",0).getBoolean("autoRetry",true)
        set(value){context.getSharedPreferences("cloud-download-prefs",0).edit().putBoolean("autoRetry",value).apply()}
    fun startQueue() { schedule() }
    private fun schedule() {
        if (!ready) return
        synchronized(lock) {
            if (updatingEngine || preparingQueue) return
            if (active.isEmpty() && _items.value.any { it.state == "Queued" } && engineCheckDue()) {
                preparingQueue = true
                scope.launch {
                    try { refreshEngineIfDue() }
                    finally {
                        synchronized(lock) { preparingQueue = false }
                        schedule()
                    }
                }
                return
            }
            for (item in _items.value.asReversed()) {
                if (active.size >= prefs.value.concurrency) break
                if (item.state != "Queued" || item.id in active) continue
                if(item.cloudRecord!=null && item.retryAfter>System.currentTimeMillis()) {scope.launch {delay((item.retryAfter-System.currentTimeMillis()).coerceAtLeast(1));schedule()};continue}
                if(item.cloudRecord!=null && (!cloudNetworkAllowed() || _items.value.count {it.id in active && it.cloudRecord!=null}>=cloudConcurrency)) {
                    scope.launch {delay(15000);schedule()};continue
                }
                active.add(item.id)
                change(item.id) { it.copy(state = "Downloading", line = "Connecting to YouTube…") }
                scope.launch {
                    try {
                        if(item.cloudRecord!=null) {
                            try {
                                check(CloudAccount.state.value.userId==item.accountId) { "Sign in to the task's account to retry" }
                                CloudMedia.performDownload(context,com.google.gson.JsonParser.parseString(item.cloudRecord).asJsonObject,item.playWhenReady) { percent ->
                                    checkRunning(item.id)
                                    change(item.id,save=false) {it.copy(progress=percent.toFloat(),line="Downloading cloud audio")}
                                }
                                change(item.id) {if(it.state in listOf("Paused","Cancelled")) it else it.copy(state="Completed",progress=100f,line="Verified and added to library")}
                            } catch(e: Exception) {
                                if(e !is CancellationException) {
                                    val transient=e is java.io.IOException && (e !is CloudHttpException || e.status==429 || e.status>=500)
                                    val retry=transient && cloudAutoRetry && item.cloudAttempts<item.options.retries
                                    val wait=cloudRetryDelay(item.cloudAttempts)
                                    change(item.id) {it.copy(state=if(retry) "Queued" else "Failed",cloudAttempts=it.cloudAttempts+1,retryAfter=if(retry)System.currentTimeMillis()+wait else 0,error="Cloud transfer failed",line=if(retry) "Waiting to retry" else "Cloud download failed · retry available")}
                                    if(retry)scope.launch {delay(wait);schedule()}
                                }
                            }
                        } else perform(item)
                    } finally {
                        synchronized(lock) { active.remove(item.id) }
                        schedule()
                    }
                }
            }
        }
    }
    fun hasWork(): Boolean = synchronized(lock) { active.isNotEmpty() || _items.value.any { it.state == "Queued" } }
    fun pause(id: String) {
        change(id) { if (it.terminal || it.state == "Paused") it else it.copy(state = "Paused", line = "Paused · resume to continue") }
        scope.launch { YoutubeDL.getInstance().destroyProcessById(id) }
    }
    fun cancel(id: String) {
        change(id) { if (it.terminal) it else it.copy(state = "Cancelled", line = "Download cancelled") }
        scope.launch { YoutubeDL.getInstance().destroyProcessById(id) }
    }
    fun resume(id: String) {
        synchronized(lock) { require(id !in active) { "The previous worker is stopping. Try again in a moment." } }
        change(id) { if (it.state in listOf("Paused", "Interrupted", "Failed", "Cancelled")) it.copy(state = "Queued", error = "", line = "Waiting for a download slot") else it }
        ensureService()
    }
    fun remove(id: String) {
        synchronized(lock) {
            require(id !in active) { "Stop this download before removing it." }
            _items.value = _items.value.filterNot { it.id == id }; persist()
        }
    }
    private fun current(id: String) = _items.value.first { it.id == id }
    private fun checkRunning(id: String) { if (current(id).state in listOf("Paused", "Cancelled")) throw CancellationException() }
    private suspend fun perform(item: DownloadItem) {
        val directory = File(workRoot, item.id).apply { mkdirs() }
        try {
            checkRunning(item.id)
            var failures = 0
            var completed = 0
            val sources = if (item.media.playlist) item.media.entries else listOf(item.media)
            check(sources.isNotEmpty()) { "This playlist has no downloadable items." }
            val sourceFiles = mutableListOf<String>()
            for ((index, media) in sources.withIndex()) {
                checkRunning(item.id)
                check(directory.usableSpace > 256L * 1024 * 1024) { "Storage almost full. Free at least 256 MB, then retry." }
                val prefix = if (item.media.playlist && item.options.preserveOrder) "%03d - ".format(index + 1) else ""
                val request = request(media.url, item.options).addOption("--no-playlist").addOption("--newline")
                    .addOption("--continue").addOption("--no-mtime").addOption("--restrict-filenames")
                    .addOption("--concurrent-fragments", 2).addOption("-o", File(directory, "$prefix%(title).120B [%(id)s].%(ext)s").absolutePath)
                if (item.audio) {
                    request.addOption("-f", "bestaudio/best").addOption("--extract-audio").addOption("--audio-format", "mp3")
                        .addOption("--audio-quality", if (item.quality == "best") "0" else "${item.quality}K")
                        .addOption("--embed-metadata").addOption("--embed-thumbnail").addOption("--convert-thumbnails", "jpg")
                } else {
                    val limit = if (item.quality == "best") "" else "[height<=${item.quality.toInt()}]"
                    request.addOption("-f", "bestvideo$limit[ext=mp4]+bestaudio[ext=m4a]/best$limit[ext=mp4]/best$limit")
                        .addOption("--merge-output-format", "mp4")
                }
                if (item.audio && item.media.playlist) request.addOption("--print", "after_move:__YTHUB_FILE__%(filepath)s").addOption("--no-simulate").addOption("--no-quiet")
                var lastUpdate = 0L
                try {
                    val result = YoutubeDL.getInstance().execute(request, item.id) { progress, eta, line ->
                        val time = System.currentTimeMillis()
                        if (time - lastUpdate > 250) {
                            lastUpdate = time
                            change(item.id, false) {
                                if (it.state in listOf("Paused", "Cancelled")) it else it.copy(
                                    state = if (line.contains("[ExtractAudio]") || line.contains("[Merger]")) "Processing" else "Downloading",
                                    progress = ((index + progress.coerceIn(0f, 100f) / 100f) / sources.size) * 100f,
                                    eta = eta, line = if (sources.size > 1) "${index + 1}/${sources.size} · ${line.takeLast(180)}" else line.takeLast(180))
                            }
                        }
                    }
                    if (item.audio && item.media.playlist) {
                        val path = result.out.lineSequence().lastOrNull { it.startsWith("__YTHUB_FILE__") }?.removePrefix("__YTHUB_FILE__")?.trim()
                        val saved = path?.let { File(it).canonicalFile }
                        check(saved != null && saved.parentFile == directory.canonicalFile && saved.isFile) { "Could not identify the downloaded song. Retry to finish importing the playlist." }
                        sourceFiles.add(saved.name)
                        change(item.id) { it.copy(playlistFileOrder = sourceFiles.toList()) }
                    }
                    completed++
                } catch (e: Exception) {
                    checkRunning(item.id)
                    if (!item.media.playlist) throw e
                    failures++
                }
                change(item.id) { if (it.state in listOf("Paused", "Cancelled")) it else it.copy(completedCount = completed, failedCount = failures) }
            }
            checkRunning(item.id)
            val files = directory.listFiles()?.filter { it.extension.lowercase() in listOf("mp3", "mp4", "mkv", "webm", "m4a") && it.length() > 0 }?.sortedBy { it.name } ?: emptyList()
            check(files.isNotEmpty()) { "No media was downloaded. Retry or update the engine in Settings." }
            var zipUri = ""
            if (item.media.playlist && item.options.autoZip) {
                change(item.id) { if (it.state in listOf("Paused", "Cancelled")) it else it.copy(state = "Zipping", line = "Creating playlist ZIP…") }
                val zip = File(directory, "${safeName(item.media.title)}.zip")
                val part = File(directory, "playlist.zip.part")
                ZipOutputStream(part.outputStream().buffered()).use { output ->
                    for (file in files) {
                        checkRunning(item.id)
                        output.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { checkRunning(item.id); val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read) } }
                        output.closeEntry()
                    }
                }
                check(part.renameTo(zip)) { "Unable to finalize ZIP" }
                zipUri = current(item.id).zipUri.ifBlank { export(zip, "application/zip", item) }
                change(item.id) { it.copy(zipUri = zipUri) }
            }
            checkRunning(item.id)
            change(item.id) { if (it.state in listOf("Paused", "Cancelled")) it else it.copy(state = "Exporting", line = "Saving to ${item.options.folderName}…") }
            val uris = current(item.id).uris.toMutableList()
            val exportedNames = current(item.id).exportedNames.toMutableList()
            if (item.exportsIndividualFiles(MusicRepository.state.value.settings.downloadPlaylists)) {
                for (file in files.filter { it.name !in exportedNames }) {
                    checkRunning(item.id)
                    uris.add(export(file, if (item.audio) "audio/mpeg" else "video/mp4", item))
                    exportedNames.add(file.name)
                    change(item.id) { it.copy(uris = uris.toList(), exportedNames = exportedNames.toList()) }
                }
            }
            checkRunning(item.id)
            change(item.id) { if (it.state in listOf("Paused", "Cancelled")) it else it.copy(state = if (failures == 0) "Completed" else "Failed", progress = 100f, bytes = files.sumOf { file -> file.length() },
                uris = uris.toList(), zipUri = zipUri.ifBlank { it.zipUri }, mime = if (item.audio) "audio/mpeg" else "video/mp4",
                line = "$completed downloaded${if (failures > 0) " · $failures failed" else " · saved to ${item.options.folderName}"}",
                error = if (failures > 0) "$failures playlist items failed. Retry to attempt the playlist again; existing local files will be reused." else "") }
            if (failures == 0) { directory.deleteRecursively(); DownloadService.notifyFinished(context, current(item.id)) }
            if (item.audio && (MusicRepository.state.value.settings.autoAdd || MusicRepository.state.value.settings.autoPlayDownload || item.createsLibraryPlaylist(MusicRepository.state.value.settings.downloadPlaylists))) {
                    MusicRepository.rescan(MusicRepository.state.value.settings.autoPlayDownload)
                    if (MusicRepository.state.value.settings.autoPlayDownload) withContext(Dispatchers.Main) {
                        val ids = current(item.id).orderedAudioUris().mapNotNull { uri -> MusicRepository.state.value.tracks.find { it.uri == uri }?.id }
                        if (ids.isNotEmpty()) MusicPlayback.play(ids)
                    }
            }
        } catch (e: Exception) {
            change(item.id) { if (it.state in listOf("Paused", "Cancelled")) it else it.copy(state = "Failed", error = e.message?.takeLast(1600) ?: "Download failed", line = "Tap retry to continue") }
        }
    }
    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100).ifBlank { "YTHub playlist" }
    private fun export(file: File, mime: String, item: DownloadItem): String {
        checkRunning(item.id)
        if (item.options.folderUri.isNotBlank()) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(item.options.folderUri)) ?: error("Choose your download folder again.")
            val target = tree.createFile(mime, file.name) ?: error("This folder is not writable. Choose another folder.")
            try { context.contentResolver.openOutputStream(target.uri)?.use { copyExport(file, it, item.id) } ?: error("Could not open the destination") }
            catch (e: Exception) { target.delete(); throw e }
            return target.uri.toString()
        }
        if (Build.VERSION.SDK_INT < 29) {
            // Scoped app storage is used on Android 8–9; no broad storage permission is requested.
            val target = File(workRoot, "saved/${item.id}/${file.name}").apply { parentFile?.mkdirs() }
            file.copyTo(target, overwrite = true)
            return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", target).toString()
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/YTHub/${if (mime == "application/zip") "Playlists" else if (item.audio) "Audio" else "Video"}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create the download file")
        try {
            context.contentResolver.openOutputStream(uri)?.use { copyExport(file, it, item.id) } ?: error("Could not write the download file")
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
        return uri.toString()
    }
    private fun copyExport(file: File, output: java.io.OutputStream, id: String) {
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { checkRunning(id); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) } }
    }
    fun clearTemporary(): Long = synchronized(lock) {
        require(active.isEmpty()) { "Pause or finish active downloads before clearing temporary files." }
        var bytes = 0L
        val retained = _items.value.filter { !it.terminal }.map { it.id }.toSet()
        workRoot.listFiles()?.filter { it.name != "saved" && it.name !in retained }?.forEach { directory ->
            bytes += directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }; directory.deleteRecursively()
        }
        bytes
    }
    fun temporaryBytes(): Long = workRoot.walkTopDown().filter { it.isFile && !it.path.contains("${File.separator}saved${File.separator}") }.sumOf { it.length() }
}

internal fun cloudRetryDelay(attempt:Int):Long = (2000L shl attempt.coerceIn(0,7)).coerceAtMost(300000L)
