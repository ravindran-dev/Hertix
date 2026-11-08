@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.ravindran.ythub.mobile

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

private val Accent = MobileUi.accent
@Composable internal fun NowPlayingScreen(onLibrary: () -> Unit, onSettings: () -> Unit, onDownload: (String) -> Unit, onMinimize: () -> Unit = onLibrary, onPhoneMusic: () -> Unit = onLibrary, source: String = "Library", motionProgress: Float = 1f) {
    val presentation=remember {MusicPlayback.state.map {it.copy(position=0)}.distinctUntilChanged()}
    val annotations=remember {MusicRepository.state.map {it.copy(queue=emptyList(),queueIndex=0,position=0,repeat=0,shuffle=false)}.distinctUntilChanged()}
    val player by presentation.collectAsStateWithLifecycle(initialValue=remember {MusicPlayback.state.value.copy(position=0)})
    val library by annotations.collectAsStateWithLifecycle(initialValue=remember {MusicRepository.state.value.copy(queue=emptyList(),queueIndex=0,position=0,repeat=0,shuffle=false)})
    val track = player.track
    var options by remember { mutableStateOf(false) }
    var showTrackOptions by remember { mutableStateOf(false) }
    var addPlaylist by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf("") }
    var queueName by remember { mutableStateOf("") }
    var saveQueue by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { MusicPlayback.restore() }
    if (track == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            MusicEmpty("Your next listen starts here", "Play a completed audio download or choose a song from your library.")
            Button(onClick = onLibrary, modifier = Modifier.fillMaxWidth()) { Text("Open music library") }
        }; return
    }
    val sharedPull = LocalPlayerPullOffset.current
    val dragScope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(Unit) { onDispose { settleJob?.cancel(); sharedPull.floatValue = 0f } }
    val dragThreshold = with(LocalDensity.current) { 86.dp.toPx() }
    val dragState = rememberDraggableState { delta -> sharedPull.floatValue = (sharedPull.floatValue + delta).coerceIn(0f, dragThreshold * 2.5f) }
    fun finishPlayerDrag(velocity: Float) {
        if (sharedPull.floatValue >= dragThreshold || velocity > 1400f && sharedPull.floatValue > dragThreshold / 3f) onMinimize()
        else settleJob = dragScope.launch {
            androidx.compose.animation.core.Animatable(sharedPull.floatValue).animateTo(0f, spring(dampingRatio = 1f, stiffness = 330f)) {
                sharedPull.floatValue = value
            }
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer {
        val progress = motionProgress.coerceIn(0f, 1f)
        val targetScaleY = (with(density) { 72.dp.toPx() } / size.height.coerceAtLeast(1f)).coerceIn(.06f, .16f)
        scaleX = .94f + .06f * progress
        scaleY = targetScaleY + (1f - targetScaleY) * progress
        translationY = (size.height * .5f - with(density) { 108.dp.toPx() }) * (1f - progress)
        alpha = progress
        shape = RoundedCornerShape(if (progress < .5f) 36.dp else 20.dp)
        clip = true
    }) {
    val availableHeight = maxHeight
    val availableWidth = maxWidth
    val compactTransport = availableWidth < 360.dp
    val sideControlSize = if (compactTransport) 40.dp else 48.dp
    val skipControlSize = if (compactTransport) 60.dp else 70.dp
    val playControlSize = if (compactTransport) 72.dp else if (availableHeight < 600.dp) 76.dp else 84.dp
    val skipGlyphSize = if (compactTransport) 39.dp else 43.dp
    val artworkSize = minOf(maxWidth-40.dp,maxHeight*.43f).coerceIn(128.dp,360.dp)
    val cover=remember(track,library.tracks) {
        val savedTrack = library.tracks.firstOrNull { it.id == track.id && it.artwork.isNotBlank() }
        when {
            track.artwork.isNotBlank() -> track
            savedTrack != null -> track.copy(artwork = savedTrack.artwork)
            else -> {
                val hasSpecificAlbum = track.album.isNotBlank() && track.album != "Unknown album"
                val hasSpecificArtist = track.artist.isNotBlank() && track.artist != "Unknown artist"
                val match = if (hasSpecificAlbum && hasSpecificArtist) library.tracks.firstOrNull {
                    it.artwork.isNotBlank() && it.album.equals(track.album, ignoreCase = true) && it.artist.equals(track.artist, ignoreCase = true)
                } else null
                match?.let { track.copy(artwork = it.artwork) } ?: track
            }
        }
    }
    Column(Modifier.fillMaxSize().graphicsLayer {
        val displacement = sharedPull.floatValue
        val pullFraction = (displacement / dragThreshold).coerceIn(0f, 1f) * motionProgress.coerceIn(0f, 1f)
        translationY = displacement * motionProgress.coerceIn(0f, 1f)
        scaleX = 1f - .035f * pullFraction
        scaleY = 1f - .015f * pullFraction
    }.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Keep the floating actions in layout flow so they can never cover the artwork.
        ContextControls(onBack = onMinimize, onAdd = { addPlaylist = true }, onMore = { options = true }, backLabel = "Minimize player", addLabel = "Add to playlist", backIcon = Icons.Rounded.KeyboardArrowDown)
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).draggable(
            state = dragState, orientation = Orientation.Vertical,
            onDragStarted = { settleJob?.cancel() }, onDragStopped = { velocity -> finishPlayerDrag(velocity) }
        ), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.width(34.dp).height(3.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .36f)))
            Text("Playing from $source", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(artworkSize).testTag("player-artwork-gesture").draggable(
                    state = dragState, orientation = Orientation.Vertical,
                    onDragStarted = { settleJob?.cancel() },
                    onDragStopped = { velocity -> finishPlayerDrag(velocity) }), contentAlignment = Alignment.Center) {
                    TrackArtwork(cover,Modifier.size(artworkSize).clip(RoundedCornerShape(20.dp)).shadow(20.dp, RoundedCornerShape(20.dp)).testTag("player-artwork"))
                }
                Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Crossfade(track, modifier = Modifier.weight(1f), animationSpec = tween(180), label = "Song title") { shown ->
                        Column {
                            Text(shown.title, Modifier.fillMaxWidth().basicMarquee(initialDelayMillis = 850, repeatDelayMillis = 1_100, velocity = 28.dp), fontSize = if (availableWidth < 340.dp) 20.sp else 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip, color = MaterialTheme.colorScheme.onSurface)
                            Text(shown.artist, Modifier.fillMaxWidth().padding(top = 5.dp).basicMarquee(initialDelayMillis = 1_000, repeatDelayMillis = 1_100, velocity = 24.dp), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                        }
                    }
                    PlayerControl(if (track.id in library.favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Toggle favorite", { MusicRepository.favorite(track.id) }, active = track.id in library.favorites)
                }
                PlayerSeekSection(track.id)
                Row(Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    CurvedPlaybackControl("Shuffle ${if (player.shuffle) "on" else "off"}", MusicPlayback::shuffle, active = player.shuffle, size = sideControlSize, glyph = if (compactTransport) 22.dp else 25.dp, style = CurvedPlaybackGlyph.Shuffle)
                    PlayerControl(Icons.Rounded.FastRewind, "Previous track", MusicPlayback::previous, size = skipControlSize, glyph = skipGlyphSize)
                    Box(Modifier.size(playControlSize).clip(CircleShape).glassTouch(MusicPlayback::toggle).testTag("primary-playback")
                        .semantics { contentDescription = if (player.playing) "Pause" else "Play" }, contentAlignment = Alignment.Center) {
                        Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(if (compactTransport) 51.dp else 58.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    PlayerControl(Icons.Rounded.FastForward, "Next track", MusicPlayback::next, enabled = player.queue.size > 1, size = skipControlSize, glyph = skipGlyphSize)
                    LoopModeControl("Repeat ${listOf("off", "one", "all")[player.repeat]}", MusicPlayback::repeat, active = player.repeat > 0, repeatOne = player.repeat == 1, size = sideControlSize, glyph = if (compactTransport) 22.dp else 25.dp)
                }
        }
        Spacer(Modifier.height(4.dp))
        PlayerVolume(showOutputAction = false)
        PlayerActionRow(onLyrics = {panel="Lyrics"},onOutput = {panel="Output"},onQueue = {panel="Queue"})
        if(player.sleepMode!="Off")PlayerSleepStatus(player)
        Spacer(Modifier.height(12.dp))
    }
    if (panel.isNotEmpty()) GlassSheet(onDismissRequest = { panel = "" }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth()) {
        if (panel == "Lyrics") ArtworkAtmosphere(track, Modifier.matchParentSize(), strong = true)
        when (panel) {
            "Queue" -> QueuePanel(onSave = { saveQueue = true }, onDownload = onDownload)
            "Lyrics" -> LyricsPanel(track)
            "Output" -> AudioOutputPanel()
            "Info" -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Track information", style = MaterialTheme.typography.titleLarge)
                listOf("Title" to track.title, "Artist" to track.artist, "Album" to track.album, "File" to track.filename, "Format" to track.format).forEach { (label, value) ->
                    if (value.isNotBlank()) Column {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            "Audio" -> Column { MusicActionRow("All player settings", Icons.Rounded.Settings, { panel = ""; onSettings() }, forward = true); PlayerSettingsScreen(audioOnly = true) }
            "Timer" -> SleepPanel { panel = "" }
        }
        }
    }
    if (options) {
        val popupWidth = (LocalConfiguration.current.screenWidthDp - 48).coerceAtLeast(260).dp
        GlassActionPopover("PLAYER OPTIONS", onDismiss = { options = false }, panelWidth = popupWidth) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TrackArtwork(track, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(track.artist, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = Color.White.copy(alpha = .12f))
            MusicActionRow("Play next", Icons.Rounded.PlaylistPlay, { MusicPlayback.enqueue(listOf(track.id), true); options = false }, enabled = track.available)
            MusicActionRow("Add to queue", Icons.Rounded.QueueMusic, { MusicPlayback.enqueue(listOf(track.id)); options = false }, enabled = track.available)
            MusicActionRow("Add to playlist", Icons.Rounded.PlaylistAdd, { options = false; addPlaylist = true })
            MusicActionRow(if (track.id in library.favorites) "Remove favorite" else "Add favorite", if (track.id in library.favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, { MusicRepository.favorite(track.id); options = false })
            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = Color.White.copy(alpha = .12f))
            MusicActionRow("Lyrics", Icons.Rounded.Lyrics, { options = false; panel = "Lyrics" })
            MusicActionRow("Sleep timer", Icons.Rounded.Schedule, { options = false; panel = "Timer" })
            MusicActionRow("Audio output", Icons.Rounded.DevicesOther, { options = false; panel = "Output" })
            MusicActionRow("Skip backward ${library.settings.skipBackward}s", Icons.Rounded.FastRewind, { MusicPlayback.seek(MusicPlayback.state.value.position - library.settings.skipBackward * 1000); options = false })
            MusicActionRow("Skip forward ${library.settings.skipForward}s", Icons.Rounded.FastForward, { MusicPlayback.seek(MusicPlayback.state.value.position + library.settings.skipForward * 1000); options = false })
            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = Color.White.copy(alpha = .12f))
            MusicActionRow("Track information", Icons.Rounded.Info, { options = false; panel = "Info" })
            if (track.downloadId != null) MusicActionRow("Open download details", Icons.Rounded.Download, { options = false; onDownload(track.downloadId) })
            MusicActionRow("Manage file and library entry", Icons.Rounded.FolderOpen, { options = false; showTrackOptions = true })
        }
    }
    if (showTrackOptions) TrackOptions(track, onDismiss = { showTrackOptions = false }, onPlaylist = { showTrackOptions = false; addPlaylist = true }, onDownload = onDownload)
    if (addPlaylist) PlaylistPicker(listOf(track.id)) { addPlaylist = false }
    if (saveQueue) GlassConfirmDialog(onDismissRequest = { saveQueue = false }, title = { Text("Save queue as playlist") }, text = { OutlinedTextField(queueName, { queueName = it }, label = { Text("Playlist name") }) },
        confirmButton = { TextButton(enabled = queueName.isNotBlank(), onClick = { MusicRepository.playlist(queueName, player.queue); saveQueue = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { saveQueue = false }) { Text("Cancel") } })
    }
}
/** Position ticks only recompose the seek controls, never the artwork or full player. */
@Composable private fun PlayerSeekSection(trackId:String) {
    val ticks=remember(trackId) {MusicPlayback.state.map {it.position to it.duration}.distinctUntilChanged()}
    val clock by ticks.collectAsStateWithLifecycle(initialValue=remember(trackId){MusicPlayback.state.value.let {it.position to it.duration}})
    var seeking by remember(trackId) {mutableStateOf<Float?>(null)}
    val duration=clock.second.coerceAtLeast(1)
    GlassPlayerSlider(value=seeking ?: clock.first.toFloat().coerceIn(0f,duration.toFloat()),onValueChange={seeking=it},onValueChangeFinished={seeking?.let {MusicPlayback.seek(it.toLong())};seeking=null},valueRange=0f..duration.toFloat(),enabled=clock.second>0,ink=MaterialTheme.colorScheme.onSurface,showThumb=false,description="Playback position",modifier=Modifier.fillMaxWidth())
    val position=seeking?.toLong() ?: clock.first
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(durationLabel(position/1000),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("−${durationLabel(((clock.second-position)/1000).coerceAtLeast(0))}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun PlayerSleepStatus(player:PlaybackSnapshot) {
    val now by produceState(SystemClock.elapsedRealtime(),player.sleepDeadline) {while(true){value=SystemClock.elapsedRealtime();delay(1000)}}
    Text("Sleep: ${if(player.sleepDeadline>0)durationLabel(((player.sleepDeadline-now)/1000).coerceAtLeast(0)) else player.sleepMode}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
}
@Composable private fun QueuePanel(onSave: () -> Unit, onDownload: (String) -> Unit) {
    val player by MusicPlayback.state.collectAsStateWithLifecycle()
    val library by MusicRepository.state.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf<LocalTrack?>(null) }
    var optionIndex by remember { mutableIntStateOf(-1) }
    var addPlaylist by remember { mutableStateOf<LocalTrack?>(null) }
    val upcoming = remember(player.queue, player.index) { player.queue.drop(player.index) }
    val queueKeys = remember(player.queue, player.index) { motionTrackKeys(player.queue).drop(player.index) }
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Text("Player queue", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = MusicPlayback::clearUpcoming) { Text("Clear upcoming") }
            TextButton(onClick = MusicPlayback::shuffleUpcoming) { Text("Shuffle upcoming") }
            TextButton(onClick = onSave, enabled = player.queue.isNotEmpty()) { Text("Save playlist") }
        }
        Text("Hold a drag handle to reorder upcoming tracks.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(Modifier.heightIn(max = 500.dp), contentPadding = PaddingValues(12.dp)) {
            itemsIndexed(upcoming, key = { index, _ -> queueKeys[index] }) { offset, id ->
                val index = player.index + offset
                val track = library.tracks.find { it.id == id }
                Row(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(if (index == player.index) Accent.copy(alpha = .10f) else MaterialTheme.colorScheme.surface).border(1.dp, if (index == player.index) Accent.copy(alpha = .35f) else Color.White.copy(alpha = .05f), RoundedCornerShape(18.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TrackArtwork(track, Modifier.size(36.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(track?.title ?: "Unavailable track", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text(track?.artist.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); if (index == player.index) Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = Accent) }
                    if (track != null) IconButton(onClick = { optionIndex = index; options = track }) { Icon(Icons.Default.MoreHoriz, "Queue track options") }
                    if (index > player.index) {
                        Icon(Icons.Default.DragHandle, "Drag to reorder", Modifier.size(48.dp).pointerInput(index, player.queue) {
                            var distance = 0f
                            detectDragGesturesAfterLongPress(onDragStart = { distance = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }, onDragEnd = { distance = 0f }, onDragCancel = { distance = 0f }) { change, drag ->
                                change.consume(); distance += drag.y
                                if (distance > threshold && index < player.queue.lastIndex) { MusicPlayback.move(index, index + 1); distance = 0f }
                                else if (distance < -threshold && index > player.index + 1) { MusicPlayback.move(index, index - 1); distance = 0f }
                            }
                        })
                    }
                }
            }
        }
    }
    options?.let { track -> TrackOptions(track, onDismiss = { options = null }, onPlaylist = { options = null; addPlaylist = track }, onDownload = onDownload, extraActions = if (optionIndex > player.index) listOf(
        "Move up in queue" to { MusicPlayback.move(optionIndex, optionIndex - 1); options = null },
        "Remove from queue" to { MusicPlayback.remove(optionIndex); options = null }
    ) else emptyList()) }
    addPlaylist?.let { track -> PlaylistPicker(listOf(track.id)) { addPlaylist = null } }
}
@Composable private fun LyricsPanel(track: LocalTrack) {
    val player by MusicPlayback.state.collectAsStateWithLifecycle()
    val library by MusicRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val savedLyrics = library.tracks.firstOrNull { it.id == track.id }?.lyrics.orEmpty()
    val lyrics = savedLyrics.ifBlank { track.lyrics }
    val lines = remember(lyrics) { parseLyrics(lyrics) }
    val list = rememberLazyListState()
    var auto by remember { mutableStateOf(true) }
    var lookupAttempt by remember(track.id) { mutableIntStateOf(0) }
    var lookupState by remember(track.id) { mutableStateOf("loading") }
    var lookupError by remember(track.id) { mutableStateOf("") }
    val active = lines.indexOfLast { it.timeMs <= player.position }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        runCatching { MusicRepository.attachLyrics(track.id, uri) }.onFailure { MusicRepository.message.value = it.message }
    } }
    LaunchedEffect(track.id, lyrics, lookupAttempt) {
        if (lyrics.isNotBlank()) {
            lookupState = "available"
            return@LaunchedEffect
        }
        lookupState = "loading"
        lookupError = ""
        runCatching { LyricsRepository.find(track, forceLookup = lookupAttempt > 0) }
            .onSuccess { found ->
                if (found.isNullOrBlank()) lookupState = "missing"
                else {
                    MusicRepository.saveLyrics(track.id, found)
                    lookupState = "available"
                }
            }
            .onFailure { lookupError = it.message ?: "Could not look up lyrics."; lookupState = "error" }
    }
    LaunchedEffect(active, auto) { if (auto && active >= 0 && !list.isScrollInProgress) list.animateScrollToItem(active) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
        Text("Lyrics", style = MaterialTheme.typography.headlineSmall)
        if (lines.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) { TextButton(onClick = { auto = !auto }) { Text(if (auto) "Auto-scroll on" else "Auto-scroll off") }; TextButton(onClick = { auto = true; scope.launch { if (active >= 0) list.animateScrollToItem(active) } }) { Text("Current line") } }
        if (lyrics.isBlank() && lookupState == "loading") {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Searching LRCLIB for a matching track…", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (lyrics.isBlank() && lookupState == "missing") {
            MusicEmpty("No confident match yet", "We searched by song details and checked alternate title matches. You can retry or import a lyrics file.")
            TextButton(onClick = { lookupAttempt++ }) { Text("Search again") }
        } else if (lyrics.isBlank() && lookupState == "error") {
            Text(lookupError, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { lookupAttempt++ }) { Text("Try again") }
        } else if (lines.isEmpty()) Text(lyrics, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(vertical = 16.dp))
        else LazyColumn(Modifier.heightIn(max = 400.dp), state = list, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(lines) { i, line -> Text(line.text, color = if (i == active) Accent else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().clickable { MusicPlayback.seek(line.timeMs) }.padding(8.dp)) }
        }
        if (lyrics.isNotBlank() && lookupState == "available") Text("Lyrics provided by LRCLIB or your local file", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { picker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import local lyrics") }
    }
}
@Composable private fun SleepPanel(onDone: () -> Unit) {
    val player by MusicPlayback.state.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Timer, null, tint = Accent)
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
        }
        if (player.sleepMode != "Off") Text("Active · ${if (player.sleepDeadline > 0) durationLabel(((player.sleepDeadline - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)) else player.sleepMode}", color = Accent, style = MaterialTheme.typography.bodySmall)
        Text("Stop playback after", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(10, 15, 30, 45, 60).forEach { minutes ->
                OutlinedButton(onClick = { MusicPlayback.timer("Duration", minutes); onDone() }, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("$minutes min", color = MaterialTheme.colorScheme.onSurface) }
            }
        }
        Surface(shape = MobileUi.card, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)) {
            Column {
                MusicActionRow("End of current track", Icons.Rounded.MusicNote, { MusicPlayback.timer("End of track"); onDone() })
                MusicActionRow("End of playlist", Icons.Rounded.PlaylistPlay, { MusicPlayback.timer("End of playlist"); onDone() })
            }
        }
        OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(4) }, label = { Text("Custom minutes (1–1440)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        Button(onClick = { MusicPlayback.timer("Duration", custom.toInt()); onDone() }, enabled = (custom.toIntOrNull() ?: 0) in 1..1440, modifier = Modifier.fillMaxWidth()) { Text("Set custom timer") }
        if (player.sleepMode != "Off") MusicActionRow("Cancel timer", Icons.Rounded.TimerOff, { MusicPlayback.cancelTimer(); onDone() }, destructive = true)
    }
}
