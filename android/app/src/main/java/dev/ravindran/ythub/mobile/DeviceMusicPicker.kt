@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.ravindran.ythub.mobile

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media as AudioMedia
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android's shared audio index, including music saved by other apps. No copying or online lookup. */
internal object DeviceMusicCatalog {
    val permission get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun allowed(context: Context) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun query(context: Context): List<LocalTrack> {
        val columns = arrayOf(AudioMedia._ID, AudioMedia.DISPLAY_NAME, AudioMedia.TITLE, AudioMedia.ARTIST, AudioMedia.ALBUM, AudioMedia.DURATION, AudioMedia.SIZE, AudioMedia.ALBUM_ID)
        val selection = buildList {
            add("${AudioMedia.DURATION} > 0")
            if (Build.VERSION.SDK_INT >= 29) add("${AudioMedia.IS_PENDING} = 0")
            if (Build.VERSION.SDK_INT >= 30) add("${AudioMedia.IS_TRASHED} = 0")
        }.joinToString(" AND ")
        return buildList {
            context.contentResolver.query(AudioMedia.EXTERNAL_CONTENT_URI, columns, selection, null, "${AudioMedia.TITLE} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(AudioMedia.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString()
                    val filename = cursor.getString(1).orEmpty()
                    val albumId = cursor.getLong(7)
                    val art = if (albumId > 0L) "content://media/external/audio/albumart/$albumId" else ""
                    add(LocalTrack(java.util.UUID.nameUUIDFromBytes(uri.toByteArray()).toString(), uri,
                        cursor.getString(2).orEmpty().ifBlank { filename.substringBeforeLast('.') },
                        cursor.getString(3).orEmpty().takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown artist",
                        cursor.getString(4).orEmpty().takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown album",
                        filename, cursor.getLong(5), cursor.getLong(6), format = filename.substringAfterLast('.', "audio").uppercase(), artwork = art, folder = "Phone audio"))
                }
            }
        }
    }
}

@Composable internal fun DeviceMusicPicker(playlistId: String?, onDismiss: () -> Unit, onChooseFiles: () -> Unit) {
    val context = LocalContext.current
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val library by MusicRepository.state.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(DeviceMusicCatalog.allowed(context)) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var songs by remember { mutableStateOf(emptyList<LocalTrack>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var search by remember { mutableStateOf("") }
    val query = search.trim()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> access = granted; revision++ }
    LaunchedEffect(Unit) { if (!access) permission.launch(DeviceMusicCatalog.permission) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { access = DeviceMusicCatalog.allowed(context); revision++ } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(access, context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { revision++ } }
        if (access) runCatching { context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer) }.onFailure { access = false; error = "Music access changed. Allow access to browse again." }
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    LaunchedEffect(access, revision) {
        if (!access) { songs = emptyList(); return@LaunchedEffect }
        delay(250)
        loading = true; error = null
        try { songs = withContext(Dispatchers.IO) { DeviceMusicCatalog.query(context) } }
        catch (e: Exception) { error = "Could not read phone music. Check music access and try again." }
        finally { loading = false }
    }
    val visible by produceState(songs, songs, query) { value = withContext(Dispatchers.Default) { songs.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) || it.filename.contains(query, true) } } }
    val already = remember(library.tracks, library.playlists, playlistId) {
        val ids = library.playlists.firstOrNull { it.id == playlistId }?.tracks?.toSet().orEmpty()
        library.tracks.filter { it.id in ids }.map { it.uri }.toSet()
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassControlSurface(Modifier.size(44.dp), shape = MaterialTheme.shapes.large) { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library", { if (!busy) onDismiss() }) }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Phone music", style = MaterialTheme.typography.titleLarge)
                Text("Add audio saved on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (access) Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text("${songs.size}", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
        Column(Modifier.weight(1f).padding(horizontal = 18.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!keyboardOpen) Text("Choose songs from your phone and add them to your library${if (playlistId != null) " or this playlist" else ""}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!access) {
                GlassControlSurface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Allow access to your audio", style = MaterialTheme.typography.titleMedium)
                        Text("Hertix reads your device’s music list so you can choose which songs to add.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { permission.launch(DeviceMusicCatalog.permission) }, modifier = Modifier.fillMaxWidth()) { Text("Allow music access") }
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("Open app permissions") }
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                OutlinedTextField(search, { search = it }, placeholder = { Text("Search songs or artists") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${visible.size} songs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { selected = if (visible.all { it.uri in selected || it.uri in already }) selected - visible.map { it.uri }.toSet() else selected + visible.filter { it.uri !in already }.map { it.uri } }) {
                        Icon(Icons.Rounded.SelectAll, null, Modifier.size(18.dp)); Text("Select visible")
                    }
                }
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { revision++ }) { Text("Try again") } }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("phone-music-list"), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    if (visible.isEmpty() && !loading && error == null) item { Text(if (query.isBlank()) "No audio found yet. You can also choose a file below." else "No matching songs.", Modifier.padding(vertical = 20.dp)) }
                    items(visible, key = { it.uri }, contentType = { "phone-song" }) { song ->
                        val libraryArt = remember(song.uri, song.title, song.artist, library.tracks) {
                            library.tracks.firstOrNull { it.uri == song.uri && it.artwork.isNotBlank() }
                                ?: library.tracks.firstOrNull { it.artwork.isNotBlank() && it.title.equals(song.title, true) && it.artist.equals(song.artist, true) }
                        }
                        val artworkSong = if (song.artwork.isBlank() && libraryArt != null) song.copy(artwork = libraryArt.artwork) else song
                        val added = song.uri in already
                        val checked = added || song.uri in selected
                        fun toggle() { selected = if (song.uri in selected) selected - song.uri else selected + song.uri }
                        GlassControlSurface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tintAlpha = if (checked) .3f else .15f) {
                            Row(Modifier.fillMaxWidth().glassTouch(onClick = { toggle() }, enabled = !added && !busy).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                TrackArtwork(artworkSong, Modifier.size(44.dp), MaterialTheme.shapes.medium)
                                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (added) "Already in playlist" else "${song.artist} · ${durationLabel(song.durationMs / 1000)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (added) Icon(Icons.Rounded.Check, "Already added", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                else Checkbox(checked, { toggle() }, enabled = !busy)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(onClick = onChooseFiles, enabled = !busy, modifier = Modifier.weight(.78f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.extraLarge) { Text("Browse files", maxLines = 1) }
            Button(onClick = {
                busy = true
                scope.launch {
                    try {
                        val ids = MusicRepository.importAudio(songs.filter { it.uri in selected && it.uri !in already }.map { Uri.parse(it.uri) })
                        if (ids.isNotEmpty()) { if (playlistId != null) MusicRepository.addToPlaylist(playlistId, ids); onDismiss() }
                        else error = "Those files could not be opened. Refresh or choose another song."
                    } finally { busy = false }
                }
            }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.weight(1.22f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.extraLarge) {
                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(if (busy) "Adding…" else "Add ${selected.size}", maxLines = 1)
            }
        }
    }
}
