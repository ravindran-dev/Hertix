package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun PlayerSettingsScreen(onStorage: () -> Unit = {}, audioOnly: Boolean = false) {
    val state by MusicRepository.state.collectAsStateWithLifecycle()
    val playback by MusicPlayback.state.collectAsStateWithLifecycle()
    val scanning by MusicRepository.scanning.collectAsStateWithLifecycle()
    val downloads by DownloadStore.prefs.collectAsStateWithLifecycle()
    val prefs = state.settings
    val scope = rememberCoroutineScope()
    var confirmation by remember { mutableStateOf("") }
    fun change(transform: (PlayerPreferences) -> PlayerPreferences) { MusicRepository.settings(transform(MusicRepository.state.value.settings)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = if (audioOnly) 32.dp else LocalMusicBottomPadding.current + 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!audioOnly) {
            Text("Make every listen yours.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            MusicSettingsSection("Playback", "Speed, position and skip controls", true) {
                MusicToggle("Remember playback position", prefs.rememberPosition) { value -> change { it.copy(rememberPosition = value) } }
                MusicToggle("Restore last track on launch", prefs.restoreTrack) { value -> change { it.copy(restoreTrack = value) } }
                MusicChoice("Speed", "${prefs.speed}x", listOf("0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0"), { value -> change { it.copy(speed = value.toFloat()) } })
                MusicChoice("Skip forward", "${prefs.skipForward}s", listOf("5", "10", "15", "30", "60"), { value -> change { it.copy(skipForward = value.toInt()) } })
                MusicChoice("Skip backward", "${prefs.skipBackward}s", listOf("5", "10", "15", "30", "60"), { value -> change { it.copy(skipBackward = value.toInt()) } })
                Text("Gapless transitions depend on the decoder and file metadata.", style = MaterialTheme.typography.bodySmall)
            }
        }
        MusicSettingsSection("Audio Effects", "Equalizer and bass boost", audioOnly) {
            MusicToggle("Equalizer", prefs.equalizer, playback.effectsAvailable) { value -> change { it.copy(equalizer = value) } }
            if (!playback.effectsAvailable) Text("Start a track to detect this device’s equalizer. Some output routes do not support effects.", style = MaterialTheme.typography.bodySmall)
            if (playback.effectsAvailable) {
                MusicChoice("Preset", prefs.preset, listOf("Flat", "Custom") + playback.equalizerPresets, { value -> change { it.copy(preset = value) } })
                playback.equalizerBands.forEachIndexed { index, hz ->
                    val levels = List(playback.equalizerBands.size) { band -> if (prefs.preset == "Custom") prefs.bands.getOrElse(band) { 0 } else playback.equalizerLevels.getOrElse(band) { 0 } }
                    Text("$hz Hz · ${levels[index] / 100f} dB", style = MaterialTheme.typography.labelMedium)
                    Slider(value = levels[index].toFloat().coerceIn(playback.equalizerRange.first.toFloat(), playback.equalizerRange.second.toFloat()), valueRange = playback.equalizerRange.first.toFloat()..playback.equalizerRange.second.toFloat(), enabled = prefs.equalizer,
                        onValueChange = { value -> change { it.copy(preset = "Custom", bands = levels.toMutableList().apply { set(index, value.toInt()) }) } }, modifier = Modifier.semantics { contentDescription = "$hz Hz equalizer gain" })
                }
            }
            Text("Bass boost${if (!playback.bassAvailable) " · unavailable on this device or route" else ""}")
            Slider(value = prefs.bass.toFloat(), onValueChange = { value -> change { it.copy(bass = value.toInt()) } }, valueRange = 0f..1000f, enabled = playback.bassAvailable, modifier = Modifier.semantics { contentDescription = "Bass boost strength" })
            Text("Crossfade, spatial effects and loudness normalization are unavailable. Audio routing follows Android’s media output controls.", style = MaterialTheme.typography.bodySmall)
        }
        if (!audioOnly) {
            MusicSettingsSection("Queue and Playlists", "Next-track behavior and downloaded playlists") {
                MusicToggle("Play next automatically", prefs.autoNext) { value -> change { it.copy(autoNext = value) } }
                MusicToggle("Create downloaded playlists", prefs.downloadPlaylists) { value -> change { it.copy(downloadPlaylists = value) } }
                Text("Save and reorder the current queue from Now Playing → Queue.", style = MaterialTheme.typography.bodySmall)
            }
            MusicSettingsSection("Background Playback", "Keep listening when you leave Hertix") {
                MusicToggle("Continue in background", prefs.background) { value -> change { it.copy(background = value) } }
                Text("Android handles interruptions, audio focus and headphone unplug events. Lock-screen controls use the same playback session.", style = MaterialTheme.typography.bodySmall)
            }
            MusicSettingsSection("Notifications", "Download completion and playlist readiness") {
                MusicToggle("Download completion notifications", downloads.notifications) { DownloadStore.savePrefs(DownloadStore.prefs.value.copy(notifications = it)) }
                MusicToggle("Notify when playlist is ready", prefs.notifyPlaylist) { value -> change { it.copy(notifyPlaylist = value) } }
                Text("Android notification permission and system channel settings also apply.", style = MaterialTheme.typography.bodySmall)
            }
            MusicSettingsSection("Library and Scanning", "Import behavior and library appearance") {
                MusicToggle("Automatically add completed audio", prefs.autoAdd) { value -> change { it.copy(autoAdd = value) } }
                MusicToggle("Play after an audio download", prefs.autoPlayDownload) { value -> change { it.copy(autoPlayDownload = value) } }
                MusicChoice("Library layout", prefs.libraryLayout, listOf("Grid", "Compact"), { value -> change { it.copy(libraryLayout = value) } })
                OutlinedButton(onClick = { scope.launch { MusicRepository.rescan(true) } }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) { Text(if (scanning) "Scanning…" else "Rescan local library") }
                Text("Import audio or ZIP playlists from Library. Scanning never deletes source files.", style = MaterialTheme.typography.bodySmall)
            }
            MusicSettingsSection("Storage", "Artwork cache and temporary downloads") {
                TextButton(onClick = onStorage) { Text("Open storage overview") }
                TextButton(onClick = { confirmation = "Clear artwork cache" }) { Text("Clear artwork cache") }
                TextButton(onClick = { confirmation = "Clear temporary files" }) { Text("Clear temporary files") }
            }
            MusicSettingsSection("Privacy and Data", "Local listening history and saved preferences") {
                Text("Your library, playlists and listening history stay on this device. Lyrics use local files.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmation = "Clear playback history" }) { Text("Clear playback history") }
                TextButton(onClick = { confirmation = "Reset player preferences" }) { Text("Reset player preferences") }
            }
        }
    }
    if (confirmation.isNotBlank()) GlassConfirmDialog(onDismissRequest = { confirmation = "" }, title = { Text("$confirmation?") }, text = { Text("Your songs and playlists will be kept.") },
        dismissButton = { TextButton(onClick = { confirmation = "" }) { Text("Cancel") } }, confirmButton = { TextButton(onClick = {
            when (confirmation) {
                "Clear artwork cache" -> MusicRepository.clearArtwork()
                "Clear playback history" -> MusicRepository.update { it.copy(history = emptyMap()) }
                "Reset player preferences" -> MusicRepository.settings(PlayerPreferences())
                "Clear temporary files" -> scope.launch { runCatching { withContext(Dispatchers.IO) { DownloadStore.clearTemporary() } }.onFailure { MusicRepository.message.value = it.message } }
            }; confirmation = ""
        }) { Text("Confirm") } })
}
@Composable private fun MusicToggle(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(end = 12.dp)); Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}
