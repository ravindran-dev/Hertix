package dev.ravindran.ythub.mobile

import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ExploreScreen(
    url: String,
    onUrl: (String) -> Unit,
    audio: Boolean,
    onAudio: (Boolean) -> Unit,
    quality: String,
    onQuality: () -> Unit,
    playlistDownload: Boolean,
    onPlaylistDownload: (Boolean) -> Unit,
    preferences: Preferences,
    engineReady: Boolean,
    busy: Boolean,
    onAnalyze: () -> Unit,
    activeDownloads: Int,
    onDownloads: () -> Unit,
    onStorage: () -> Unit,
    onYouTubeSearch: () -> Unit,
    onLibraryCategory: (String) -> Unit,
    onPlaylist: (String) -> Unit
) {
    val state by MusicRepository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var playlistOptions by rememberSaveable { mutableStateOf(false) }
    fun analyze() { keyboard?.hide(); if (!busy && url.isNotBlank() && engineReady) onAnalyze() }
    val tracks = state.tracks
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Discover, download and organize music", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GlassControlSurface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), tintAlpha = .2f) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(Modifier.size(36.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .14f)) { androidx.compose.foundation.layout.Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary) } }
                    Column { Text("Download media", style = MaterialTheme.typography.titleMedium); Text("Paste a supported video or playlist link", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                OutlinedTextField(
                    value = url, onValueChange = onUrl, placeholder = { Text("YouTube link", maxLines = 1) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .64f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .46f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { analyze() }),
                    trailingIcon = {
                        IconButton(onClick = {
                            if (url.isNotBlank()) onUrl("") else {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                onUrl(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())
                            }
                        }) { Icon(if (url.isBlank()) Icons.Default.ContentPaste else Icons.Default.Close, if (url.isBlank()) "Paste link" else "Clear link") }
                    }
                )
                PrimaryButton(if (busy) "Analyzing…" else "Analyze link", Icons.Default.Search, ::analyze, !busy && url.isNotBlank() && engineReady)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                FormatSelector(audio, onAudio)
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onQuality) {
                        Icon(Icons.Default.HighQuality, null, Modifier.size(18.dp))
                        Text(qualityLabel(audio, quality), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { playlistOptions = !playlistOptions }) {
                        Text("Playlist options", style = MaterialTheme.typography.labelMedium)
                        Icon(if (playlistOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                    }
                }
                if (playlistOptions) Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .74f)) {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        ToggleRow("Download playlist", "Analyze every item in a playlist", playlistDownload, onPlaylistDownload)
                        ToggleRow("Create ZIP", "Archive completed media", preferences.autoZip) { DownloadStore.savePrefs(preferences.copy(autoZip = it)) }
                        ToggleRow("Preserve order", "Keep the original playlist sequence", preferences.preserveOrder) { DownloadStore.savePrefs(preferences.copy(preserveOrder = it)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (activeDownloads > 0) MusicPillButton("$activeDownloads downloads", Icons.Rounded.Download, onDownloads, modifier = Modifier.weight(1f))
                    MusicPillButton("${preferences.folderName.ifBlank { "Save location" }}", Icons.Rounded.FolderOpen, onStorage, modifier = Modifier.weight(1f), primary = false)
                }
            }
        }
        GlassControlSurface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), tintAlpha = .18f) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(Modifier.size(42.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .14f)) { androidx.compose.foundation.layout.Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary) } }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Find new music", style = MaterialTheme.typography.titleMedium)
                    Text("Search YouTube songs, videos and playlists", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                IconButton(onClick = onYouTubeSearch) { Icon(Icons.Rounded.ArrowForward, "Search YouTube", tint = MaterialTheme.colorScheme.primary) }
            }
        }
        if (tracks.isNotEmpty()) {
            MusicShelf("Recently added", tracks.sortedByDescending { it.added }, "Your downloaded music will appear here.", { onLibraryCategory("Recently Added") }) { track ->
                MusicPlayback.play(listOf(track.id)); onLibraryCategory("Songs")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicSectionTitle("Your playlists", action = "See all", onAction = { onLibraryCategory("Playlists") })
            if (state.playlists.isEmpty()) Text("Create a playlist from your library.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else state.playlists.take(5).forEach { playlist -> PlaylistArtworkRow(playlist, tracks) { onPlaylist(playlist.id) } }
        }
        Spacer(Modifier.height(LocalMusicBottomPadding.current + 18.dp))
    }
}
