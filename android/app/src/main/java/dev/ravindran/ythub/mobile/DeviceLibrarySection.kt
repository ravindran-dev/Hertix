package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.gson.JsonObject

@Composable
fun DeviceLibrarySection() {
    val context=LocalContext.current
    val sync by CloudSync.state.collectAsStateWithLifecycle()
    val music by MusicRepository.state.collectAsStateWithLifecycle()
    val transfer by CloudMedia.state.collectAsStateWithLifecycle()
    var records by remember {mutableStateOf<List<JsonObject>>(emptyList())}
    var states by remember {mutableStateOf<Map<String,Pair<FileAvailability,String?>>>(emptyMap())}
    var selected by remember {mutableStateOf<Set<String>>(emptySet())}
    val tasks by DownloadStore.items.collectAsStateWithLifecycle()
    var error by remember {mutableStateOf("")}
    LaunchedEffect(sync.rows,music.tracks,transfer.busy) {
        if(transfer.busy)return@LaunchedEffect
        try {
            records=CloudMedia.files().filter {it.get("status").asString=="ready"}
            states=withContext(Dispatchers.IO) {
                sync.rows["library_items"].orEmpty().filter {it.get("deleted_at")?.isJsonNull!=false}.associate {row ->
                    val id=row.get("id").asString
                    val localId=CloudSync.localTrackId(id)
                    val local=music.tracks.find {it.id==localId && !it.uri.startsWith("cloud://")}
                    val objectId=row.get("cloud_object_id")?.takeUnless {it.isJsonNull}?.asString
                    val cloud=records.find {it.get("id").asString==objectId}
                    val evidence=local?.let {DeviceFileVerifier.verify(context,it,cloud?.get("bytes")?.asLong,cloud?.get("sha256")?.asString)}
                    id to (LibraryAvailability.derive(AccountSongEvidence(id,cloud!=null),evidence) to localId)
                }
            }
            error=""
        } catch(_:Exception) {error="Unable to check cloud files. Local playback remains available."}
    }
    val available=states.values.count {it.first in setOf(FileAvailability.available_local,FileAvailability.available_local_and_cloud)}
    val missing=states.values.count {it.first==FileAvailability.missing_local_downloadable}
    val downloadable=sync.rows["library_items"].orEmpty().filter {states[it.get("id").asString]?.first==FileAvailability.missing_local_downloadable}
    fun schedule(ids:Set<String>) {downloadable.filter {it.get("id").asString in ids}.forEach {row -> records.find {it.get("id").asString==row.get("cloud_object_id")?.asString}?.let {CloudMedia.download(context.applicationContext,it)}}}
    val cloudTasks=tasks.filter {it.cloudRecord!=null && it.accountId==CloudAccount.state.value.userId}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=LocalMusicBottomPadding.current+20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
    item {
    Text("Online library",style=MaterialTheme.typography.titleMedium)
    Text("${states.size} cloud song records · $available offline · $missing audio files ready to download",style=MaterialTheme.typography.bodySmall)
    Row {TextButton(onClick={selected=downloadable.map {it.get("id").asString}.toSet()}){Text("Select all")};TextButton(onClick={selected=emptySet()}){Text("Select none")}}
    Button(enabled=downloadable.isNotEmpty(),onClick={schedule(downloadable.map {it.get("id").asString}.toSet())}){Text("Download all missing")}
    if(selected.isNotEmpty()) OutlinedButton(onClick={schedule(selected);selected=emptySet()}){Text("Download selected (${selected.size})")}
    if(cloudTasks.any {it.working || it.state=="Queued"}) Text("${cloudTasks.count {it.state=="Completed"}} completed · ${cloudTasks.count {it.working || it.state=="Queued"}} pending",style=MaterialTheme.typography.bodySmall)
    if(cloudTasks.any {it.state=="Failed"}) TextButton(onClick={cloudTasks.filter {it.state=="Failed"}.forEach {DownloadStore.resume(it.id)}}){Text("Retry failed")}
    if(error.isNotEmpty())Text(error)
    }
    items(sync.rows["library_items"].orEmpty().filter {it.get("deleted_at")?.isJsonNull!=false},key={it.get("id").asString}) {row ->
        val id=row.get("id").asString
        val state=states[id]
        val objectId=row.get("cloud_object_id")?.takeUnless {it.isJsonNull}?.asString
        val cloud=records.find {it.get("id").asString==objectId}
        Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            if(state?.first==FileAvailability.missing_local_downloadable) Checkbox(checked=id in selected,onCheckedChange={selected=if(it)selected+id else selected-id})
            val task=cloudTasks.find {it.media.url=="cloud://${cloud?.get("id")?.asString}"}
            if(task!=null && task.state!="Completed") {Text("${task.state} · ${task.progress.toInt()}%",style=MaterialTheme.typography.bodySmall);if(task.working || task.state=="Queued") TextButton(onClick={DownloadStore.cancel(task.id)}){Text("Cancel")}}
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TrackArtwork(music.tracks.find {it.id==state?.second},Modifier.size(48.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(row.get("title").asString,style=MaterialTheme.typography.titleSmall,maxLines=2)
                    Text(row.get("artist").asString,style=MaterialTheme.typography.bodySmall,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(state?.first in setOf(FileAvailability.available_local,FileAvailability.available_local_and_cloud)) {
                TextButton(onClick={state?.second?.let {MusicPlayback.play(listOf(it))}}){Text("Play on this device")}
            } else if(cloud!=null) {
                Row {TextButton(enabled=!transfer.busy,onClick={CloudMedia.download(context.applicationContext,cloud)}){Text("Download")};TextButton(enabled=!transfer.busy,onClick={CloudMedia.download(context.applicationContext,cloud,true)}){Text("Download & Play")}}
            } else Text("This song is in your account library, but its audio file is not stored in the cloud.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
}
