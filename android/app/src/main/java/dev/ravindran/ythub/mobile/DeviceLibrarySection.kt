package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
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
    val context = LocalContext.current
    val sync by CloudSync.state.collectAsStateWithLifecycle()
    val music by MusicRepository.state.collectAsStateWithLifecycle()
    val transfer by CloudMedia.state.collectAsStateWithLifecycle()
    val tasks by DownloadStore.items.collectAsStateWithLifecycle()
    var records by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var states by remember { mutableStateOf<Map<String, Pair<FileAvailability, String?>>>(emptyMap()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(sync.rows, music.tracks, transfer.busy) {
        if (transfer.busy) return@LaunchedEffect
        try {
            records = CloudMedia.files().filter { it.get("status").asString == "ready" }
            states = withContext(Dispatchers.IO) {
                sync.rows["library_items"].orEmpty().filter { it.get("deleted_at")?.isJsonNull != false }.associate { row ->
                    val id = row.get("id").asString
                    val localId = CloudSync.localTrackId(id)
                    val local = music.tracks.find { it.id == localId && !it.uri.startsWith("cloud://") }
                    val objectId = row.get("cloud_object_id")?.takeUnless { it.isJsonNull }?.asString
                    val cloud = records.find { it.get("id").asString == objectId }
                    val evidence = local?.let { DeviceFileVerifier.verify(context, it, cloud?.get("bytes")?.asLong, cloud?.get("sha256")?.asString) }
                    id to (LibraryAvailability.derive(AccountSongEvidence(id, cloud != null), evidence) to localId)
                }
            }
            error = ""
        } catch (_: Exception) { error = "Could not refresh cloud availability. Your local music is safe." }
    }

    val available = states.values.count { it.first in setOf(FileAvailability.available_local, FileAvailability.available_local_and_cloud) }
    val missing = states.values.count { it.first == FileAvailability.missing_local_downloadable }
    val rows = sync.rows["library_items"].orEmpty().filter { it.get("deleted_at")?.isJsonNull != false }
    val downloadable = rows.filter { states[it.get("id").asString]?.first == FileAvailability.missing_local_downloadable }
    val cloudTasks = tasks.filter { it.cloudRecord != null && it.accountId == CloudAccount.state.value.userId }
    fun schedule(ids: Set<String>) {
        downloadable.filter { it.get("id").asString in ids }.forEach { row ->
            records.find { it.get("id").asString == row.get("cloud_object_id")?.asString }?.let { CloudMedia.download(context.applicationContext, it) }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalMusicBottomPadding.current + 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            GlassControlSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), tintAlpha = .2f) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("${states.size}" to "In cloud", "$available" to "On device", "$missing" to "To download").forEach { (value, label) ->
                            Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .6f)) {
                                Column(Modifier.padding(horizontal = 9.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(value, style = MaterialTheme.typography.titleMedium)
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = downloadable.isNotEmpty() && !transfer.busy, onClick = { schedule(downloadable.map { it.get("id").asString }.toSet()) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("Download missing", maxLines = 1) }
                        if (downloadable.isNotEmpty()) OutlinedButton(onClick = { selected = if (selected.size == downloadable.size) emptySet() else downloadable.map { it.get("id").asString }.toSet() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text(if (selected.size == downloadable.size) "Clear selection" else "Select all", maxLines = 1) }
                    }
                    if (selected.isNotEmpty()) OutlinedButton(onClick = { schedule(selected); selected = emptySet() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Download selected · ${selected.size}") }
                    if (cloudTasks.any { it.working || it.state == "Queued" }) Text("${cloudTasks.count { it.state == "Completed" }} complete · ${cloudTasks.count { it.working || it.state == "Queued" }} in progress", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (cloudTasks.any { it.state == "Failed" }) TextButton(onClick = { cloudTasks.filter { it.state == "Failed" }.forEach { DownloadStore.resume(it.id) } }) { Text("Retry failed downloads") }
                    if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (rows.isEmpty()) item {
            MusicEmpty("Your online library is empty", "Songs synced to your account will appear here.")
        }
        items(rows, key = { it.get("id").asString }) { row ->
            val id = row.get("id").asString
            val state = states[id]
            val objectId = row.get("cloud_object_id")?.takeUnless { it.isJsonNull }?.asString
            val cloud = records.find { it.get("id").asString == objectId }
            val task = cloudTasks.find { it.media.url == "cloud://${cloud?.get("id")?.asString}" }
            GlassControlSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), tintAlpha = .14f) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (task != null && task.state != "Completed") {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${task.state} · ${task.progress.toInt()}%", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            if (task.working || task.state == "Queued") TextButton(onClick = { DownloadStore.cancel(task.id) }) { Text("Cancel") }
                        }
                        LinearProgressIndicator({ task.progress / 100f }, Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (state?.first == FileAvailability.missing_local_downloadable) Checkbox(checked = id in selected, onCheckedChange = { selected = if (it) selected + id else selected - id })
                        TrackArtwork(music.tracks.find { it.id == state?.second }, Modifier.size(50.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(row.get("title").asString, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(row.get("artist").asString, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val badge = when (state?.first) {
                            FileAvailability.available_local, FileAvailability.available_local_and_cloud -> "Offline"
                            FileAvailability.missing_local_downloadable -> "Online"
                            else -> "Cloud"
                        }
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .8f)) { Text(badge, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    when {
                        state?.first in setOf(FileAvailability.available_local, FileAvailability.available_local_and_cloud) -> MusicPillButton("Play on this device", Icons.Rounded.PlayArrow, { state?.second?.let { MusicPlayback.play(listOf(it)) } }, modifier = Modifier.fillMaxWidth())
                        cloud != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MusicPillButton("Download", Icons.Rounded.Download, { CloudMedia.download(context.applicationContext, cloud) }, modifier = Modifier.weight(1f), enabled = !transfer.busy)
                            MusicPillButton("Download & Play", Icons.Rounded.PlayArrow, { CloudMedia.download(context.applicationContext, cloud, true) }, modifier = Modifier.weight(1f), enabled = !transfer.busy, primary = false)
                        }
                        else -> Text("Metadata only · audio file isn’t stored in cloud", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
