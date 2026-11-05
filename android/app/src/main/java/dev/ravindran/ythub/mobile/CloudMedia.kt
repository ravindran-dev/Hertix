package dev.ravindran.ythub.mobile

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

data class CloudTransfer(val busy: Boolean=false,val progress: Int=0,val message: String="")
object CloudMedia {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(CloudTransfer())
    val state=mutable.asStateFlow()
    private var job: Job?=null
    private val uploadLock=Mutex()
    private val mimeTypes=mapOf("mp3" to "audio/mpeg","m4a" to "audio/mp4","flac" to "audio/flac","ogg" to "audio/ogg","opus" to "audio/ogg","wav" to "audio/wav")
    suspend fun files():List<JsonObject> {
        val owner=CloudAccount.state.value.userId ?: error("Sign in first")
        val rows=mutableListOf<JsonObject>();var offset=0
        while(true) { val page=CloudAccount.api("/rest/v1/cloud_objects?status=neq.deleted&order=id&limit=500&offset=$offset",expectedOwner=owner).asJsonArray.map {it.asJsonObject}; rows+=page;if(page.size<500)break;offset+=500 }
        return rows
    }
    suspend fun usage()=CloudAccount.api("/rest/v1/rpc/storage_usage","POST",JsonObject()).asJsonObject
    suspend fun action(action: String,id: String,owner:String?=null)=CloudAccount.api("/functions/v1/account-files","POST",json("action" to action,"object_id" to id),owner)
    fun cancel() {job?.cancel()}
    private fun start(block: suspend ()->Unit) {
        if(job?.isActive==true)return
        mutable.value=CloudTransfer(true)
        job=scope.launch {try {block();mutable.value=CloudTransfer(message="Transfer confirmed")} catch(_:CancellationException){mutable.value=CloudTransfer(message="Cancelled locally. Remove any pending cloud reservation when connected.")} catch(e:Exception){mutable.value=CloudTransfer(message=e.message ?: "Transfer failed; retry available")}}
    }
    private fun hex(bytes:ByteArray)=bytes.joinToString("") { "%02x".format(it) }
    fun upload(context:Context,track:LocalTrack,retry:JsonObject?=null) = start {
        uploadFile(context,track,retry) { mutable.value=CloudTransfer(true,it) }
    }
    suspend fun uploadFile(context:Context,track:LocalTrack,retry:JsonObject?=null,progress:(Int)->Unit={}) = uploadLock.withLock {
        val owner=CloudAccount.state.value.userId ?: error("Sign in first")
        val usage=usage();val max=usage.get("max_file_bytes").asLong
        val mime=mimeTypes[track.format.lowercase()] ?: mimeTypes[track.filename.substringAfterLast('.').lowercase()] ?: error("Supported formats: MP3, M4A, FLAC, OGG, Opus and WAV")
        val temporary=File.createTempFile("cloud-upload-",".audio",context.cacheDir)
        var reservation:String?=null
        try {
            val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
            context.contentResolver.openInputStream(Uri.parse(track.uri))?.use { input -> temporary.outputStream().use { output ->
                val buffer=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=max){"File exceeds the server's per-file limit"};digest.update(buffer,0,n);output.write(buffer,0,n)}
            } } ?: error("Local file unavailable")
            require(bytes>0){"File is empty"};val checksum=hex(digest.digest())
            if(retry!=null) require(retry.get("sha256").asString==checksum && retry.get("bytes").asLong==bytes){"Choose the original file to retry"}
            check(CloudAccount.state.value.userId==owner){"Account changed"}
            val item=retry?.get("library_item_id")?.asString ?: CloudSync.ensureTrack(track)
            val objectId=retry?.get("id")?.asString ?: UUID.randomUUID().toString()
            val result=CloudAccount.api("/rest/v1/rpc/reserve_upload","POST",json("item_id" to item,"object_id" to objectId,"file_name" to track.filename.ifBlank { "${track.title}.${track.format.lowercase()}" }.replace('/','_').replace('\\','_').take(255),"checksum" to checksum,"mime" to mime).apply {addProperty("file_bytes",bytes)},owner).asJsonObject
            reservation=result.get("id").asString
            if(result.get("status").asString=="ready")return@withLock
            if(result.get("status").asString=="pending") {
                val connection=connection("/storage/v1/object/music-private/${result.get("object_path").asString}","POST",owner)
                try {
                    connection.setRequestProperty("Content-Type",mime);connection.doOutput=true;connection.setFixedLengthStreamingMode(bytes)
                    temporary.inputStream().use { input -> connection.outputStream.use { output -> val buffer=ByteArray(65536);var sent=0L;while(true){currentCoroutineContext().ensureActive();check(CloudBackupPolicy.networkAllowed(context)){"Waiting for allowed network"};check(CloudAccount.state.value.userId==owner){"Account changed"};val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n);sent+=n;progress((sent*95/bytes).toInt())} } }
                    if(connection.responseCode !in 200..299 && connection.responseCode!=409)throw CloudHttpException(connection.responseCode)
                } finally {connection.disconnect()}
            }
            currentCoroutineContext().ensureActive();check(CloudAccount.state.value.userId==owner){"Account changed"}
            action("complete",reservation,owner);CloudSync.retry()
        } catch(e:CancellationException) {
            if(reservation!=null && CloudAccount.state.value.userId==owner && CloudBackupPolicy.networkAllowed(context)) withContext(NonCancellable){runCatching {action("delete",reservation,owner)}}
            throw e
        } finally {temporary.delete()}
    }
    fun download(context:Context,record:JsonObject,playWhenReady:Boolean=false) { DownloadStore.enqueueCloud(record,playWhenReady) }
    suspend fun performDownload(context:Context,record:JsonObject,playWhenReady:Boolean=false,progress:(Int)->Unit = {}) {
        val owner=CloudAccount.state.value.userId ?: error("Sign in first")
        val mapped=CloudSync.localTrackId(record.get("library_item_id").asString)
        val existing=MusicRepository.state.value.tracks.find {it.id==mapped && !it.uri.startsWith("cloud://")}
        if(existing!=null && DeviceFileVerifier.verify(context,existing,record.get("bytes").asLong,record.get("sha256").asString).let {it.readable && it.sizeMatches}) {
            if(playWhenReady)withContext(Dispatchers.Main){MusicPlayback.play(listOf(existing.id))}
            return
        }
        val expected=record.get("bytes").asLong;require(expected in 1..26214400){"File exceeds download limit"}
        val path=record.get("object_path").asString
        require(path=="$owner/${record.get("id").asString}"){"Invalid object path"}
        val base=if(DownloadStore.cloudExternalStorage) context.getExternalFilesDir(null) ?: error("External app storage unavailable") else context.filesDir
        val dir=File(base,if(DownloadStore.cloudExternalStorage) "media/cloud" else "music/imports").apply {mkdirs()}
        require(dir.usableSpace > expected + DownloadStore.cloudStorageReserveMb * 1024L * 1024L) { "Not enough free storage" }
        val extension=mimeTypes.entries.firstOrNull {it.value==record.get("mime_type").asString}?.key ?: error("Unsupported format")
        val file=File(dir,"cloud-${UUID.randomUUID()}.$extension")
        var imported=false
        try {
            val connection=connection("/storage/v1/object/authenticated/music-private/$path","GET",owner)
            val digest=MessageDigest.getInstance("SHA-256");var received=0L
            try {
                if(connection.responseCode !in 200..299)throw CloudHttpException(connection.responseCode)
                connection.inputStream.use { input -> file.outputStream().use {output ->val buffer=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;received+=n;require(received<=expected){"Download size mismatch"};digest.update(buffer,0,n);output.write(buffer,0,n);progress((received*95/expected).toInt())}} }
            } finally {connection.disconnect()}
            require(received==expected && hex(digest.digest())==record.get("sha256").asString){"Integrity verification failed"}
            check(CloudAccount.state.value.userId==owner){"Account changed"}
            progress(99)
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
            val ids=MusicRepository.importAudio(listOf(uri))
            imported=ids.isNotEmpty()
            ids.firstOrNull()?.let {CloudSync.attachLocal(record.get("library_item_id").asString,it);if(playWhenReady) withContext(Dispatchers.Main) {MusicPlayback.play(listOf(it))}} ?: error("Downloaded audio could not be imported")
        } finally {if(!imported)file.delete()}
    }
    private suspend fun connection(path:String,method:String,owner:String):HttpURLConnection {
        val token=CloudAccount.token(owner)
        return (URL(BuildConfig.SUPABASE_URL.trimEnd('/')+path).openConnection() as HttpURLConnection).apply {requestMethod=method;connectTimeout=15000;readTimeout=20000;instanceFollowRedirects=false;setRequestProperty("apikey",BuildConfig.SUPABASE_PUBLISHABLE_KEY);setRequestProperty("Authorization","Bearer $token")}
    }
}
