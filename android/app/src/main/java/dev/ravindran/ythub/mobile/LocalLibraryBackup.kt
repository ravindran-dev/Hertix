package dev.ravindran.ythub.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LocalLibraryBackup() {
    val ready by MusicRepository.ready.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val snapshot = CloudSync.localSnapshot(MusicRepository.state.value)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(Gson().toJson(snapshot)) } ?: error("Cannot open backup")
                }
                message = "Library backup saved. Audio files are not included."
            } catch (_: Exception) { message = "Backup failed. Choose a writable file and retry." }
            finally { busy = false }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val restored = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 16 * 1024 * 1024); output.write(buffer, 0, n) }
                        output.toByteArray()
                    } ?: error("Cannot open backup")
                    require(bytes.size <= 16 * 1024 * 1024)
                    val text = bytes.toString(Charsets.UTF_8)
                    require(com.google.gson.JsonParser.parseString(text).asJsonObject.has("version"))
                    val state = MusicPersistence.decode(text)
                    state.copy(tracks = state.tracks.map { it.copy(available = MusicRepository.exists(it.uri)) })
                }
                MusicRepository.restoreLibrary(restored)
                message = "Playlists and favorites restored. Songs need their original audio files on this phone."
            } catch (_: Exception) { message = "Restore failed. Choose a valid Hertix local library backup (including a legacy YTHub backup)." }
            finally { busy = false }
        }
    }
    Text("Library backup", style = MaterialTheme.typography.titleMedium)
    Text("Save playlists, favorites and song details. Audio and account credentials are excluded. Restore merges the library; matching playlist IDs use the backup version.", style = MaterialTheme.typography.bodySmall)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        AccountActionButton(if(busy) "Preparing backup…" else "Save library backup", Icons.Outlined.Cloud,
            { export.launch("hertix-library.json") }, subtitle="Playlists, favorites and song details", enabled=!busy&&ready)
        AccountActionButton("Restore library backup", Icons.Outlined.Download,
            { restore.launch(arrayOf("application/json")) }, subtitle="Merge a saved library copy", enabled=!busy&&ready)
        if(message.isNotBlank()) Text(message, Modifier.padding(horizontal=4.dp), style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
