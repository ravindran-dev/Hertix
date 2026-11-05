package dev.ravindran.ythub.mobile

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** Durable music annotations and metadata cache. DownloadStore remains the download source of truth. */
object MusicRepository {
    private lateinit var context: Context
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanLock = Mutex()
    private val stateLock = Any()
    private val _state = MutableStateFlow(MusicState())
    val state = _state.asStateFlow()
    val message = MutableStateFlow<String?>(null)
    val scanning = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    private var writable = true
    private val saveRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val root get() = File(context.filesDir, "music").apply { mkdirs() }
    private val file get() = AtomicFile(File(root, "library-v1.json"))
    fun init(ctx: Context) {
        context = ctx.applicationContext
        scope.launch {
            try {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                    val text = file.openRead().bufferedReader().use { it.readText() }
                    _state.value = MusicPersistence.decode(text)
                }
            } catch (e: Exception) {
                writable = false
                message.value = "Music library could not be loaded. Its saved data has been preserved: ${e.message}"
            } finally { ready.value = true }
            saveRequests.receiveAsFlow().collect {
                if (!writable) return@collect
                val snapshot = synchronized(stateLock) { CloudSync.localSnapshot(_state.value) }
                var output: java.io.FileOutputStream? = null
                try { output = file.startWrite(); output.write(gson.toJson(snapshot).toByteArray()); file.finishWrite(output) }
                catch (e: Exception) { output?.let { file.failWrite(it) }; message.value = "Could not save music library: ${e.message}" }
            }
        }
        scope.launch {
            ready.first { it }
            DownloadStore.items.map { jobs -> jobs.filter { it.hasImportableAudio() } }.distinctUntilChanged().collect {
                rescan()
            }
        }
    }
    fun update(change: (MusicState) -> MusicState) = changeState(change)
    private fun localUpdate(change: (MusicState) -> MusicState) = changeState(change)
    private fun changeState(change: (MusicState) -> MusicState) = synchronized(stateLock) {
        val next = change(_state.value)
        if (next != _state.value) { _state.value = next; saveRequests.trySend(Unit) }
        Unit
    }
    fun settings(value: PlayerPreferences) {
        require(value.speed in listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f))
        update { it.copy(settings = value) }; MusicPlayback.applySettings()
    }
    internal fun editLocal(change: (MusicState) -> MusicState) = localUpdate(change)
    fun favorite(id: String) = localUpdate { it.copy(favorites = if (id in it.favorites) it.favorites - id else it.favorites + id) }
    fun remove(ids: Set<String>) = localUpdate { it.copy(tracks = it.tracks.filterNot { track -> track.id in ids }, hidden = it.hidden + ids, favorites = it.favorites - ids,
        playlists = it.playlists.map { p -> p.copy(tracks = p.tracks.filterNot { id -> id in ids }) }) }
    fun playlist(name: String, ids: List<String>, id: String? = null) {
        require(name.isNotBlank()) { "Enter a playlist name" }
        localUpdate { state ->
            val p = state.playlists.find { it.id == id }?.copy(name = name.trim(), tracks = ids)
                ?: LocalPlaylist(id = id ?: UUID.randomUUID().toString(), name = name.trim(), tracks = ids)
            state.copy(playlists = state.playlists.filterNot { it.id == p.id } + p)
        }
    }
    fun restoreLibrary(restored: MusicState) = localUpdate { current ->
        current.copy(tracks = MusicRules.merge(current.tracks, restored.tracks),
            playlists = current.playlists.filterNot { p -> restored.playlists.any { it.id == p.id } } + restored.playlists,
            favorites = current.favorites + restored.favorites)
    }
    fun deletePlaylist(id: String) = localUpdate { it.copy(playlists = it.playlists.filterNot { p -> p.id == id }) }
    fun addToPlaylist(id: String, tracks: List<String>) = localUpdate { it.copy(playlists = it.playlists.map { p -> if (p.id == id) p.copy(tracks = p.tracks + tracks.filterNot { t -> t in p.tracks }) else p }) }
    fun played(id: String) = update { val previous = it.history[id] ?: PlayRecord(); it.copy(history = it.history + (id to PlayRecord(previous.count + 1, System.currentTimeMillis()))) }
    fun exists(uri: String): Boolean = runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false }.getOrDefault(false)
    private fun metadata(uri: String, downloadId: String? = null, fallback: String = ""): LocalTrack {
        val parsed = Uri.parse(uri)
        var filename = fallback.ifBlank { parsed.lastPathSegment.orEmpty() }; var size = 0L
        context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) { filename = cursor.getString(0) ?: filename; if (!cursor.isNull(1)) size = cursor.getLong(1) }
        }
        require(MusicRules.audioName(filename) || context.contentResolver.getType(parsed)?.startsWith("audio/") == true) { "Choose a supported audio file" }
        context.contentResolver.openAssetFileDescriptor(parsed, "r")?.close() ?: error("File is unavailable")
        val id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString()
        val embeddedLyrics = if (filename.endsWith(".mp3", true)) runCatching { context.contentResolver.openInputStream(parsed)?.use(EmbeddedLyrics::read) }.getOrNull().orEmpty() else ""
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, parsed)
            fun field(key: Int) = reader.extractMetadata(key).orEmpty()
            val art = reader.embeddedPicture?.takeIf { it.size <= 8 * 1024 * 1024 }?.let { bytes -> File(root, "artwork/$id.jpg").apply { parentFile?.mkdirs(); writeBytes(bytes); MusicPersistence.trimArtwork(parentFile!!) }.absolutePath }.orEmpty()
            return LocalTrack(id, uri, field(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { filename.substringBeforeLast('.') },
                field(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { "Unknown artist" }, field(MediaMetadataRetriever.METADATA_KEY_ALBUM).ifBlank { "Unknown album" }, filename,
                field(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0, size, field(MediaMetadataRetriever.METADATA_KEY_BITRATE).toLongOrNull() ?: 0,
                filename.substringAfterLast('.', "audio").uppercase(), art, downloadId = downloadId, lyrics = embeddedLyrics, folder = if (downloadId == null) runCatching { android.provider.DocumentsContract.getDocumentId(parsed).substringBeforeLast('/', "Imported audio") }.getOrDefault("Imported audio") else DownloadStore.items.value.find { it.id == downloadId }?.options?.folderName ?: "Downloaded audio")
        } finally { reader.release() }
    }
    suspend fun downloadedTracks(id: String): List<String> = withContext(Dispatchers.IO) {
        ready.first { it }
        val job = DownloadStore.items.value.find { it.id == id && it.audio && it.state == "Completed" } ?: error("Audio download is not complete")
        val tracks = job.orderedAudioUris().mapNotNull { uri -> runCatching { metadata(uri, job.id, job.exportedNames.getOrNull(job.uris.indexOf(uri)).orEmpty()) }.getOrNull() }
        update { it.copy(tracks = MusicRules.merge(it.tracks, tracks), hidden = it.hidden - tracks.map { t -> t.id }.toSet()) }
        tracks.map { it.id }
    }
    suspend fun rescan(forceDownloads: Boolean = false, downloads: List<DownloadItem> = DownloadStore.items.value): Unit = withContext(Dispatchers.IO) { scanLock.withLock {
        ready.first { it }
        scanning.value = true
        try {
            val old = _state.value
            val incoming = mutableListOf<LocalTrack>()
            val downloadedPlaylists = mutableListOf<Pair<DownloadItem, List<String>>>()
            val byUri = old.tracks.associateBy { it.uri }
            val completed = downloads.filter { it.hasImportableAudio() }
            for (job in completed) {
                if (!old.settings.autoAdd && !forceDownloads && job.createLibraryPlaylist != true) continue
                val ids = mutableListOf<String>()
                for (uri in job.orderedAudioUris()) {
                    val i = job.uris.indexOf(uri)
                    val cached = byUri[uri]
                    val track = runCatching { if (!forceDownloads && cached != null && exists(uri)) cached.copy(available = true) else metadata(uri, job.id, job.exportedNames.getOrNull(i).orEmpty()) }.getOrNull() ?: continue
                    ids.add(track.id)
                    if (track.id !in old.hidden) incoming.add(track)
                }
                downloadedPlaylists.add(job to ids)
            }
            val checked = old.tracks.associate { it.id to exists(it.uri) }
            update { latest ->
                val merged = latest.copy(tracks = MusicRules.merge(latest.tracks.map { it.copy(available = checked[it.id] ?: it.available) }, incoming.filterNot { it.id in latest.hidden }))
                downloadedPlaylists.fold(merged) { state, (job, ids) -> state.withDownloadPlaylist(job, ids.filterNot { it in state.hidden }) }
            }
        } catch (e: Exception) { message.value = "Library scan failed: ${e.message}" }
        finally { scanning.value = false }
    } }
    suspend fun importAudio(uris: List<Uri>) = withContext(Dispatchers.IO) {
        ready.first { it }
        val tracks = uris.map { uri ->
            runCatching {
                if (android.provider.DocumentsContract.isDocumentUri(context, uri)) context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                metadata(uri.toString())
            }
                .onFailure { message.value = "Import failed: ${it.message}" }.getOrNull()
        }.filterNotNull()
        update { it.copy(tracks = MusicRules.merge(it.tracks, tracks), hidden = it.hidden - tracks.map { t -> t.id }.toSet()) }
        tracks.map { it.id }
    }
    suspend fun attachLyrics(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytesWithLimit(1024 * 1024); String(bytes, Charsets.UTF_8)
        } ?: error("Could not read lyrics")
        update { it.copy(tracks = it.tracks.map { t -> if (t.id == id) t.copy(lyrics = text) else t }) }
    }
    /** Each import gets an isolated private directory; no existing destination is overwritten. */
    suspend fun extractPlaylist(uri: Uri, name: String) = withContext(Dispatchers.IO) { scanLock.withLock {
        val destination = File(root, "imports/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var count = 0; var total = 0L
            val files = mutableListOf<File>()
            context.contentResolver.openInputStream(uri)?.use { stream -> ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 2000) { "Archive contains too many entries" }
                    val target = MusicRules.archiveTarget(destination, entry.name)
                    if (!entry.isDirectory && (MusicRules.audioName(entry.name) || entry.name.endsWith(".lrc", true) || entry.name.endsWith(".txt", true))) {
                        require(!target.exists()) { "Duplicate archive destination" }; target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(65536); var fileBytes = 0L
                            while (true) { val n = zip.read(buffer); if (n < 0) break; fileBytes += n; total += n
                                require(fileBytes <= 512L * 1024 * 1024 && total <= 2L * 1024 * 1024 * 1024 && destination.usableSpace > 64L * 1024 * 1024) { "Archive exceeds safe extraction limits" }
                                output.write(buffer, 0, n)
                            }
                        }; files.add(target)
                    } else if (!entry.isDirectory) {
                        // Read ignored entries with the same total expansion budget.
                        val buffer = ByteArray(65536)
                        while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total <= 2L * 1024 * 1024 * 1024) { "Archive exceeds safe extraction limits" } }
                    }
                    zip.closeEntry()
                }
            } } ?: error("Archive is unavailable")
            val tracks = files.filter { MusicRules.audioName(it.name) }.sortedBy { it.relativeTo(destination).path }.map { file ->
                val track = metadata(FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString())
                val lyrics = listOf(File(file.parentFile, "${file.nameWithoutExtension}.lrc"), File(file.parentFile, "${file.nameWithoutExtension}.txt")).firstOrNull { it.exists() }
                track.copy(folder = "$name / ${file.parentFile?.relativeTo(destination)?.path.orEmpty()}", lyrics = lyrics?.inputStream()?.use { String(it.readBytesWithLimit(1024 * 1024), Charsets.UTF_8) }.orEmpty())
            }
            require(tracks.isNotEmpty()) { "No supported audio files found" }
            localUpdate { it.copy(tracks = MusicRules.merge(it.tracks, tracks), playlists = it.playlists + LocalPlaylist(name = name, tracks = tracks.map { t -> t.id })) }
        } catch (e: Exception) { destination.deleteRecursively(); throw e }
    } }
    fun clearArtwork() { scope.launch { File(root, "artwork").deleteRecursively(); update { it.copy(tracks = it.tracks.map { t -> t.copy(artwork = "") }) } } }
    fun artworkBytes() = File(root, "artwork").walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
private fun java.io.InputStream.readBytesWithLimit(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= limit) { "File is too large" }; output.write(buffer, 0, n) }
    return output.toByteArray()
}
