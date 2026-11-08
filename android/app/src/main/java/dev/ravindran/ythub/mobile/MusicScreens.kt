@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.ravindran.ythub.mobile

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private val MusicRed = MobileUi.accent
private val MusicShape = MobileUi.card

@Composable internal fun TrackArtwork(track: LocalTrack?, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)) {
    val identity=track?.album?.takeUnless {it.isBlank() || it=="Unknown album"} ?: track?.artist?.takeUnless {it.isBlank() || it=="Unknown artist"} ?: track?.id.orEmpty()
    val palette=remember(identity) {
        val colors=listOf(Color(0xFF496B70),Color(0xFF58628A),Color(0xFF896456),Color(0xFF725C7D),Color(0xFF697350),Color(0xFF476C86))
        colors[Math.floorMod(identity.hashCode(),colors.size)]
    }
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(palette,palette.copy(red=palette.red*.38f,green=palette.green*.38f,blue=palette.blue*.38f)))).drawBehind {
        drawCircle(Color.White.copy(alpha=.045f),radius=size.minDimension*.40f,center=Offset(size.width*.78f,size.height*.18f))
        drawCircle(Color.White.copy(alpha=.035f),radius=size.minDimension*.55f,center=Offset(size.width*.10f,size.height*.95f))
    },contentAlignment=Alignment.Center) {
        Icon(Icons.Rounded.Album,null,Modifier.fillMaxSize(.34f),tint=Color.White.copy(alpha=.55f))
        if(track?.artwork?.isNotBlank()==true) AsyncImage(remember(track.artwork) {
            when {
                track.artwork.startsWith("content://") -> Uri.parse(track.artwork)
                track.artwork.startsWith("http://") || track.artwork.startsWith("https://") -> track.artwork
                else -> File(track.artwork)
            }
        },"Album artwork",Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
    }
}
@Composable
internal fun LibrarySourceToggle(onOnline: () -> Unit) {
    GlassControlSurface(Modifier.width(130.dp).height(38.dp), shape = RoundedCornerShape(50), tintAlpha = .28f, flexible = false) {
        Row(Modifier.fillMaxSize().padding(3.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).background(MusicRed).semantics { selected = true }, contentAlignment = Alignment.Center) {
                Text("Offline", style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).clickable(onClick = onOnline), contentAlignment = Alignment.Center) {
                Text("Online", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SearchSourceSwitch(youtubeSelected: Boolean, onSelectYouTube: () -> Unit, onSelectLibrary: () -> Unit) {
    GlassControlSurface(Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(50), tintAlpha = .34f, flexible = false) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
            val segment = maxWidth / 2
            val target = if (youtubeSelected) 0.dp else segment
            val position by animateDpAsState(target, spring(dampingRatio = .82f, stiffness = 480f), label = "Search source pill")
            Box(Modifier.offset(x = position).width(segment).fillMaxHeight().clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = .20f)).border(.5.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(50)))
            Row(Modifier.fillMaxSize()) {
                listOf("YouTube" to onSelectYouTube, "Library" to onSelectLibrary).forEach { (label, action) ->
                    val selected = (label == "YouTube") == youtubeSelected
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).glassTouch(action), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else .78f), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable internal fun MusicLibraryScreen(onDetailChanged: (Boolean) -> Unit = {}, onPhoneImportChanged: (Boolean) -> Unit = {}, onPlayer: () -> Unit, onDownload: (String) -> Unit, rootRequested: Boolean = false, onRootConsumed: () -> Unit = {}, requestedPlaylist: String? = null, onPlaylistConsumed: () -> Unit = {}, requestedCategory: String? = null, onCategoryConsumed: () -> Unit = {}, searchRequested: Boolean = false, youtubeSearchRequested: Boolean = false, onSearchChanged: (Boolean) -> Unit = {}, onOnline: () -> Unit = {}, onSearchConsumed: () -> Unit = {}, onYoutubeSearchConsumed: () -> Unit = {}, phoneMusicRequested: Boolean = false, onPhoneMusicConsumed: () -> Unit = {}) {
    val libraryFlow = remember { MusicRepository.state.map { it.copy(queue = emptyList(), queueIndex = 0, position = 0, repeat = 0, shuffle = false) }.distinctUntilChanged() }
    val state by libraryFlow.collectAsStateWithLifecycle(initialValue = remember { MusicRepository.state.value })
    val scanning by MusicRepository.scanning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val pageScroll = LocalPageScroll.current
    LaunchedEffect(Unit) { MusicRepository.restoreMissingArtwork() }
    val dock=LocalDockGlass.current
    LaunchedEffect(listState,dock) {snapshotFlow {listState.isScrollInProgress}.collect {dock?.scrolling=it}}
    DisposableEffect(dock) {onDispose {dock?.scrolling=false}}
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searchPrefs = remember(context) { context.getSharedPreferences("ythub-search-history", android.content.Context.MODE_PRIVATE) }
    var recentSearches by remember { mutableStateOf(searchPrefs.getString("queries", "").orEmpty().split('\n').filter { it.isNotBlank() }) }
    var youtubeMode by rememberSaveable { mutableStateOf(true) }
    var youtubeResults by remember { mutableStateOf<List<YouTubeResult>>(emptyList()) }
    var youtubeError by remember { mutableStateOf("") }
    var youtubeLoading by remember { mutableStateOf(false) }
    var downloadingYouTube by remember { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf("Overview") }
    var sort by rememberSaveable { mutableStateOf("Recently added") }
    var source by rememberSaveable { mutableStateOf("All sources") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistId by rememberSaveable { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<LocalTrack?>(null) }
    var optionsIndex by remember { mutableIntStateOf(-1) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var addTracks by remember { mutableStateOf<List<String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var playlistMenu by remember { mutableStateOf(false) }
    var libraryMenu by remember { mutableStateOf(false) }
    var devicePicker by rememberSaveable { mutableStateOf(false) }
    var devicePlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(phoneMusicRequested) {
        if (phoneMusicRequested) { devicePlaylist = null; devicePicker = true; onPhoneMusicConsumed() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val destination = devicePlaylist
        scope.launch { val ids = MusicRepository.importAudio(uris); if (destination != null) MusicRepository.addToPlaylist(destination, ids) }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        runCatching { MusicRepository.extractPlaylist(uri, "Imported playlist") }.onFailure { MusicRepository.message.value = it.message }
    } }
    LaunchedEffect(rootRequested) {
        if (rootRequested) {
            searching = false; query = ""; category = "Overview"; source = "All sources"
            group = null; playlistId = null; selected = emptySet()
            listState.scrollToItem(0); keyboard?.hide(); focusManager.clearFocus(); onRootConsumed()
        }
    }
    LaunchedEffect(requestedPlaylist) { if (requestedPlaylist != null) { playlistId = requestedPlaylist; category = "Playlists"; searching = false; onPlaylistConsumed() } }
    LaunchedEffect(requestedCategory) { requestedCategory?.let { category = if (it == "Songs") "All Songs" else if (it == "Downloaded") "All Songs" else it; source = if (it == "Downloaded") "Downloaded" else "All sources"; playlistId = null; group = null; searching = false; onCategoryConsumed() } }
    LaunchedEffect(searchRequested) { if (searchRequested) { searching = true; youtubeMode = true; category = "All Songs"; source = "All sources"; playlistId = null; group = null; onSearchConsumed() } }
    LaunchedEffect(youtubeSearchRequested) { if (youtubeSearchRequested) { searching = true; youtubeMode = true; category = "All Songs"; source = "All sources"; playlistId = null; group = null; onYoutubeSearchConsumed() } }
    var previousSection by rememberSaveable { mutableStateOf("$category:$playlistId:$group") }
    LaunchedEffect(category, playlistId, group) { pageScroll?.reset(); val section = "$category:$playlistId:$group"; if (section != previousSection) { listState.scrollToItem(0); previousSection = section } }
    LaunchedEffect(searching) { onSearchChanged(searching); if (searching) { listState.scrollToItem(0); withFrameNanos { }; searchFocus.requestFocus() } }
    LaunchedEffect(query, youtubeMode) {
        if (!youtubeMode || query.trim().length < 2) { youtubeResults = emptyList(); youtubeError = ""; youtubeLoading = false }
        else {
            youtubeLoading = true
            youtubeError = ""
            try { youtubeResults = searchYouTube(query.trim()) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { youtubeResults = emptyList(); youtubeError = e.message ?: "YouTube search failed" }
            finally { youtubeLoading = false }
        }
    }
    LaunchedEffect(searching, query) {
        val normalized = query.trim()
        if (searching && normalized.length >= 2) {
            delay(650)
            recentSearches = (listOf(normalized) + recentSearches.filterNot { it.equals(normalized, true) }).take(8)
            searchPrefs.edit().putString("queries", recentSearches.joinToString("\n")).apply()
        }
    }
    val sectionReveal = remember { Animatable(1f) }
    LaunchedEffect(playlistId, group, category, searching, devicePicker) {
        onDetailChanged(devicePicker || playlistId != null || group != null || category != "Overview" && !searching)
        sectionReveal.snapTo(1f)
    }
    LaunchedEffect(devicePicker) { onPhoneImportChanged(devicePicker) }
    fun libraryBack() {
        when { playlistId != null -> playlistId = null; group != null -> group = null; searching -> { searching = false; query = "" }; else -> category = "Overview" }
    }
    fun enqueueYouTubeAudio(result: YouTubeResult) {
        if (downloadingYouTube != null) return
        downloadingYouTube = result.id
        scope.launch {
            try {
                val media = DownloadStore.analyze(result.url, false)
                val id = DownloadStore.enqueue(media, true, DownloadStore.prefs.value.audioQuality)
                onDownload(id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MusicRepository.message.value = error.message ?: "Could not add this item to downloads"
            } finally {
                downloadingYouTube = null
            }
        }
    }
    BackHandler(enabled = playlistId != null || group != null || searching || category != "Overview", onBack = ::libraryBack)
    val allById = remember(state.tracks) { state.tracks.associateBy { it.id } }
    val playlist = state.playlists.find { it.id == playlistId }
    val effectiveLayout = if (category == "All Songs") state.settings.songsLayout else state.settings.libraryLayout
    val searchQuery = query.trim()
    val searchIndex = remember(state.tracks) { state.tracks.associate { track -> track.id to "${track.title} ${track.artist} ${track.album} ${track.filename}".lowercase() } }
    val normalizedQuery = remember(searchQuery) { searchQuery.lowercase() }
    val playlistIds = remember(playlist) { playlist?.tracks?.toSet() }
    val base by produceState(emptyList<LocalTrack>(), state.tracks, state.favorites, state.history, source, category, playlistIds, group, searchQuery) {
        if (searchQuery.isNotBlank()) kotlinx.coroutines.delay(120)
        value = withContext(Dispatchers.Default) { state.tracks.filter { track ->
        (source == "All sources" || (source == "Downloaded") == (track.downloadId != null)) &&
        (category != "Favorites" || track.id in state.favorites) &&
        (category != "Recently Played" || track.id in state.history) &&
        (playlistIds == null || track.id in playlistIds) &&
        (group == null || when (category) { "Albums" -> MusicRules.albumGroupKey(track) == group || (group?.contains("\u001f") != true && track.album == group); "Artists" -> track.artist == group; "Folders" -> track.folder == group; else -> true }) &&
        (normalizedQuery.isBlank() || searchIndex[track.id].orEmpty().contains(normalizedQuery))
    } } }
    val byId = remember(base) { base.associateBy { it.id } }
    val sortedTracks = produceState(emptyList<LocalTrack>(), base, playlist, sort, category, state.history) { value = withContext(Dispatchers.Default) { if (playlist != null) playlist.tracks.mapNotNull { id -> byId[id] } else when (if (category == "Recently Played") "Recently played" else if (category == "Recently Added") "Recently added" else sort) {
        "Title" -> base.sortedBy { it.title.lowercase() }; "Artist" -> base.sortedBy { it.artist.lowercase() }
        "Duration" -> base.sortedByDescending { it.durationMs }; "File size" -> base.sortedByDescending { it.bytes }
        "Recently played" -> base.sortedByDescending { state.history[it.id]?.lastPlayed ?: 0 }; else -> base.sortedByDescending { it.added }
    } } }
    // The lazy item count and occurrence keys must come from the same snapshot.
    val tracks = sortedTracks.value
    val recentPlayed = remember(state.tracks, state.history) { state.tracks.filter { it.id in state.history }.sortedByDescending { state.history[it.id]?.lastPlayed }.take(12) }
    val recentAdded = remember(state.tracks) { state.tracks.sortedByDescending { it.added }.take(12) }
    val trackKeys = remember(tracks) { motionTrackKeys(tracks.map { it.id }) }
    Box(Modifier.fillMaxSize()) {
    if (devicePicker) {
        DeviceMusicPicker(devicePlaylist, onDismiss = { devicePicker = false }, onChooseFiles = { devicePicker = false; picker.launch(arrayOf("audio/*")) })
    } else {
    LazyColumn(Modifier.fillMaxSize().graphicsLayer {
        translationY = (1f - sectionReveal.value) * 250f
    }.testTag("music-library-list"), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (playlist != null || !searching && (category != "Overview" || group != null)) 60.dp else 0.dp, bottom = LocalMusicBottomPadding.current + 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playlist != null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    PlaylistCover(playlist, state.tracks, Modifier.size(216.dp))
                }
            }
            if(playlist==null && group!=null && category in listOf("Albums","Artists")) {
                val members=state.tracks.filter { if(category=="Albums") MusicRules.albumGroupKey(it)==group || (group?.contains("\u001f") != true && it.album==group) else it.artist==group }
                Box(Modifier.fillMaxWidth().padding(top=8.dp,bottom=18.dp),contentAlignment=Alignment.Center) {
                    TrackArtwork(members.firstOrNull {it.artwork.isNotBlank()} ?: members.firstOrNull(),Modifier.size(196.dp),shape=if(category=="Artists") androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(12.dp))
                }
            }
            if (playlist != null || group != null || category != "Overview" && !searching) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(playlist?.name ?: group?.let { if(category=="Albums") MusicRules.albumGroupTitle(it).let { title -> if(title=="Unknown album") "Unsorted songs" else title } else if(it=="Unknown album") "Unsorted songs" else it } ?: if (category == "All Songs") "Songs" else category, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (!(playlist == null && !searching && category == "Overview")) Text(if (searching) "Find songs, artists, albums or files" else if (playlist == null) when {
                category == "Playlists" -> "${state.playlists.size} playlists"
                category == "Albums" && group == null -> "${base.map { MusicRules.albumGroupKey(it) }.distinct().size} albums"
                category == "Artists" && group == null -> "${base.map { it.artist }.distinct().size} artists"
                else -> "${tracks.size} songs · offline and yours"
            } else "${playlist.tracks.size} tracks · ${durationLabel(playlist.tracks.mapNotNull { id -> allById[id] }.sumOf { it.durationMs } / 1000)} · ${if (playlist.downloadId != null) "Downloaded" else "Local playlist"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).testTag("library-summary"))
            if (playlist == null && !searching) Row(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Playlists", "Artists", "Albums", "Songs", "Downloaded", "Favorites", "Recently Added", "Recently Played").forEach { tab ->
                    val categoryForTab = if (tab == "Songs") "All Songs" else tab
                    val selectedTab = category == categoryForTab && (tab != "Downloaded" || source == "Downloaded")
                    FilterChip(selected = selectedTab, onClick = {
                        category = if (tab == "Songs") "All Songs" else tab
                        source = if (tab == "Downloaded") "Downloaded" else "All sources"
                        group = null; playlistId = null
                    }, label = { Text(tab, maxLines = 1) })
                }
            }
            if (playlist == null && !searching && category == "Overview") Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { devicePlaylist = playlist?.id; devicePicker = true }) { Icon(Icons.Default.Add, null); Text("Phone music") }
                IconButton(onClick = { scope.launch { MusicRepository.rescan(true) } }, enabled = !scanning) { Icon(Icons.Default.Refresh, "Rescan library") }
                IconButton(onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed")) }) { Icon(Icons.Default.FolderZip, "Extract and import playlist ZIP") }
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (searching) TextField(query, { query = it },
                placeholder = { Text("Artists, songs, lyrics and more", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(searchFocus).testTag("music-search-field")
                    .semantics { contentDescription = "Music search input" },
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = MusicRed),
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(22.dp), tint = MusicRed) },
                trailingIcon = { Row {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "Clear music search", Modifier.size(20.dp)) }
                    IconButton(onClick = { query = ""; searching = false; selected = emptySet(); keyboard?.hide(); focusManager.clearFocus() }) { Icon(Icons.Rounded.Close, "Close music search", Modifier.size(20.dp)) }
                } }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focusManager.clearFocus() }))
            if (searching) SearchSourceSwitch(youtubeSelected = youtubeMode, onSelectYouTube = { youtubeMode = true }, onSelectLibrary = { youtubeMode = false })

            }
        }
        if (searching && searchQuery.isBlank() && recentSearches.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent searches", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { recentSearches = emptyList(); searchPrefs.edit().remove("queries").apply() }) { Text("Clear") }
                }
                recentSearches.forEach { recent ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(14.dp)).glassTouch({ query = recent }).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(recent, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { recentSearches = recentSearches - recent; searchPrefs.edit().putString("queries", recentSearches.joinToString("\n")).apply() }) { Icon(Icons.Rounded.Close, "Remove $recent from recent searches", Modifier.size(18.dp)) }
                    }
                }
            }
        }
        if (searching && searchQuery.isNotBlank()) {
            if (youtubeMode) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("YouTube results", style = MaterialTheme.typography.titleMedium)
                            Text(if (youtubeLoading) "Searching securely…" else "${youtubeResults.size} ${if (youtubeResults.size == 1) "result" else "results"} · Open online or save audio",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (youtubeLoading) CircularProgressIndicator(Modifier.size(19.dp), color = MobileUi.accent, strokeWidth = 2.dp)
                    }
                }
                if (youtubeError.isNotBlank()) item {
                    GlassControlSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), tintAlpha = .22f) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                            Text(youtubeError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (!youtubeLoading && youtubeError.isBlank() && youtubeResults.isEmpty()) item {
                    GlassControlSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), tintAlpha = .18f) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.MusicNote, null, tint = MobileUi.accent, modifier = Modifier.size(25.dp))
                            Text("No YouTube results", style = MaterialTheme.typography.titleSmall)
                            Text("Try a different title, artist, or keyword.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                itemsIndexed(youtubeResults, key = { _, r -> "youtube-${r.type}-${r.id}" }) { _, result ->
                    GlassControlSurface(Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(22.dp), flexible = false, tintAlpha = .25f) {
                        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(104.dp, 76.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                    AsyncImage(result.thumbnail, "${result.title} thumbnail", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                    Surface(Modifier.align(Alignment.BottomStart).padding(5.dp), color = Color.Black.copy(alpha = .78f), shape = RoundedCornerShape(7.dp)) {
                                        Text(result.type.uppercase(), Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
                                    }
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(result.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                                    Text(result.channel.ifBlank { "YouTube channel" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Icon(Icons.Rounded.Public, null, Modifier.size(13.dp), tint = MobileUi.accent)
                                        Text("Online source · metadata only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { openYouTubeLink(context, result.url) }, modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)) {
                                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Open", maxLines = 1, softWrap = false)
                                }
                                if (result.type == "video") Button(onClick = { enqueueYouTubeAudio(result) }, modifier = Modifier.weight(1.15f).heightIn(min = 44.dp), enabled = downloadingYouTube == null, shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)) {
                                        Icon(if (downloadingYouTube == result.id) Icons.Rounded.Downloading else Icons.Rounded.Download, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(if (downloadingYouTube == result.id) "Preparing" else "Save audio", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            } else {
            itemsIndexed(state.playlists.filter { it.name.contains(searchQuery, true) }, key = { _, p -> "search-playlist-${p.id}" }) { _, p ->
                PlaylistArtworkRow(p, state.tracks) { searching = false; query = ""; category = "Playlists"; playlistId = p.id }
            }
            }
        }
        if (category == "Overview") {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    listOf("All Songs" to Icons.Rounded.MusicNote, "Playlists" to Icons.Rounded.LibraryMusic,
                        "Albums" to Icons.Rounded.Album, "Artists" to Icons.Rounded.Person,
                        "Favorites" to Icons.Rounded.FavoriteBorder, "Recently Added" to Icons.Rounded.History).chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { (destination, icon) ->
                                GlassControlSurface(Modifier.weight(1f).heightIn(min = 52.dp)) {
                                    Row(Modifier.fillMaxWidth().glassTouch({ category = destination; group = null; selected = emptySet() }).padding(horizontal = 14.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(icon, null, Modifier.size(20.dp), tint = MusicRed)
                                        Text(when (destination) { "All Songs" -> "Songs"; "Recently Added" -> "Recent"; else -> destination }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { MusicShelf("Recently Played", recentPlayed, "Your recent listens will appear here.", { category = "Recently Played" }) { track -> MusicPlayback.play(listOf(track.id)); onPlayer() } }
            item { MusicShelf("Recently Added", recentAdded, "Download or import audio to start your collection.", { category = "Recently Added" }) { track -> MusicPlayback.play(listOf(track.id)); onPlayer() } }

            item { MusicSectionTitle("Your Playlists", action = "Manage", onAction = { category = "Playlists" }) }
            if (state.playlists.isEmpty()) item { MusicPillButton("Create playlist", Icons.Rounded.Add, { create = true; name = "" }, primary = false) }
            itemsIndexed(state.playlists.take(4), key = { _, p -> p.id }, contentType = { _, _ -> "playlist" }) { _, p -> PlaylistArtworkRow(p, state.tracks) { category = "Playlists"; playlistId = p.id; searching = false; query = "" } }
        } else if (category == "Playlists" && playlist == null) {
            item { MusicPillButton("Create playlist", Icons.Rounded.Add, { create = true; name = "" }) }
            itemsIndexed(state.playlists, key = { _, p -> p.id }, contentType = { _, _ -> "playlist" }) { _, p ->
                PlaylistArtworkRow(p, state.tracks) { playlistId = p.id; searching = false; query = "" }
            }
        } else if (category in listOf("Albums", "Artists", "Folders") && group == null) {
            val groups = base.groupBy { when (category) { "Albums" -> MusicRules.albumGroupKey(it); "Artists" -> it.artist; else -> it.folder } }
            if(category=="Albums") {
                itemsIndexed(groups.keys.sorted().chunked(2),key={index,_->"album-pair-$index"}) {_, names ->
                    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        names.forEach { name ->
                            val members=groups[name].orEmpty()
                            val cover=members.firstOrNull {it.artwork.isNotBlank()} ?: members.first()
                            val albumTitle=MusicRules.albumGroupTitle(name)
                            val artist=MusicRules.albumGroupArtist(name).takeUnless {it.isBlank() || it=="Unknown artist"} ?: "Unknown artist"
                            MusicArtworkCard(cover.copy(title=if(albumTitle=="Unknown album") "Unsorted songs" else albumTitle,available=true),subtitle="$artist · ${members.size} ${if(members.size==1) "song" else "songs"}",modifier=Modifier.weight(1f)) {group=name}
                        }
                        if(names.size==1)Spacer(Modifier.weight(1f))
                    }
                }
            } else itemsIndexed(groups.keys.sorted(),key={_,key->"group-$key"}) {_,key ->
                val members=groups[key].orEmpty()
                val cover=members.firstOrNull {it.artwork.isNotBlank()} ?: members.firstOrNull()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).glassTouch({group=key}).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    TrackArtwork(cover,Modifier.size(60.dp),shape=if(category=="Artists") androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(10.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(key,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("${members.size} ${if(members.size==1) "song" else "songs"} · ${durationLabel(members.sumOf {it.durationMs}/1000)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            item {
                if (!searching) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicPillButton("Play all", Icons.Rounded.PlayArrow, { MusicPlayback.play(playlist?.tracks ?: tracks.map { it.id }); onPlayer() }, enabled = tracks.any { it.available })
                    MusicPillButton("Shuffle", Icons.Rounded.Shuffle, { MusicPlayback.play((playlist?.tracks ?: tracks.filter { it.available }.map { it.id }).shuffled()); onPlayer() }, enabled = tracks.any { it.available }, primary = false)
                }
                if (selected.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { MusicPlayback.enqueue(selected.toList()); selected = emptySet() }) { Text("Queue ${selected.size}") }
                    TextButton(onClick = { MusicPlayback.enqueue(selected.toList(), true); selected = emptySet() }) { Text("Play next") }
                    TextButton(onClick = { selected.forEach(MusicRepository::favorite); selected = emptySet() }) { Text("Toggle favorites") }
                    TextButton(onClick = { addTracks = selected.toList() }) { Text("Add to playlist") }
                    TextButton(onClick = { MusicRepository.remove(selected); selected = emptySet() }) { Text("Remove from library") }
                }
                if (tracks.isEmpty()) MusicEmpty(if (query.isNotBlank()) "No matching tracks" else "No music here yet", if (query.isNotBlank()) "Try another title, artist, album or filename." else "Download audio or import local files. Your songs remain available offline.")
            }
            if (playlist == null && searching) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (searching && query.isNotBlank()) "${tracks.size} ${if (tracks.size == 1) "result" else "results"}" else "${tracks.size} tracks", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("music-track-count"))
                    if (searching) {
                        TextButton(onClick = { MusicPlayback.play(tracks.map { it.id }); keyboard?.hide(); onPlayer() }, enabled = tracks.any { it.available }) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                            Text(if (query.isBlank()) "Play all" else "Play results", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (!searching && effectiveLayout == "Grid" && playlist == null && selected.isEmpty()) {
                itemsIndexed(tracks.chunked(2), key = { index, _ -> "grid-$index" }, contentType = { _, _ -> "artwork-pair" }) { _, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { track -> Column(Modifier.weight(1f)) {
                            MusicArtworkCard(track, modifier = Modifier.fillMaxWidth(), onMore = { options = track }) { MusicPlayback.play(tracks.map { it.id }, tracks.indexOf(track)); onPlayer() }
                        } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

            } else itemsIndexed(tracks, key = { index, _ -> trackKeys[index] }, contentType = { _, _ -> "track" }) { index, track ->
                MusicTrackRow(track, number = playlist?.let { MusicRules.occurrenceIndex(it.tracks, tracks.map { t -> t.id }, index) + 1 }, selected = track.id in selected, selectionActive = selected.isNotEmpty(), showCheckbox = selected.isNotEmpty(), onSelect = { selected = if (track.id in selected) selected - track.id else selected + track.id },
                        onPlay = { MusicPlayback.play(playlist?.tracks ?: tracks.map { it.id }, if (playlist != null) MusicRules.occurrenceIndex(playlist.tracks, tracks.map { it.id }, index) else index); onPlayer() }, onMore = { optionsIndex = if (playlist != null) MusicRules.occurrenceIndex(playlist.tracks, tracks.map { it.id }, index) else -1; options = track })
            }
            if (tracks.any { !it.available }) item { TextButton(onClick = { MusicRepository.remove(tracks.filterNot { it.available }.map { it.id }.toSet()) }) { Text("Remove unavailable entries") } }
        }
    }
    if (playlist != null || !searching && (category != "Overview" || group != null)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("fixed-library-glass-actions")) {
            ContextControls(onBack = ::libraryBack,
                onAdd = { devicePlaylist = playlist?.id; devicePicker = true },
                onMore = { if (playlist != null) playlistMenu = true else libraryMenu = true },
                addLabel = if (playlist != null) "Add phone music to playlist" else "Phone music")
            if (libraryMenu) GlassActionPopover("Library options", { libraryMenu = false }) {
                GlassMenuItem(text = { Text("Import music from phone") }, onClick = { devicePlaylist = null; devicePicker = true; libraryMenu = false })
                GlassMenuItem(text = { Text("Create playlist") }, onClick = { create = true; name = ""; libraryMenu = false })
                listOf("Title", "Artist", "Recently added", "Recently played", "Duration", "File size").forEach { choice ->
                    GlassMenuItem(text = { Text("Sort: $choice") }, onClick = { sort = choice; libraryMenu = false })
                }
                listOf("All sources", "Downloaded", "Imported").forEach { choice ->
                    GlassMenuItem(text = { Text(choice) }, onClick = { source = choice; libraryMenu = false })
                }
                GlassMenuItem(text = { Text(if (effectiveLayout == "Grid") "Use compact list" else "Use artwork grid") }, onClick = {
                    val next = if (effectiveLayout == "Grid") "Compact" else "Grid"
                    MusicRepository.settings(if (category == "All Songs") state.settings.copy(songsLayout = next) else state.settings.copy(libraryLayout = next)); libraryMenu = false
                })
            }
            if (playlistMenu && playlist != null) GlassActionPopover(playlist.name, { playlistMenu = false }) {
                GlassMenuItem(text = { Text("Add phone music") }, onClick = { devicePlaylist = playlist.id; devicePicker = true; playlistMenu = false })
                GlassMenuItem(text = { Text("Add playlist to queue") }, onClick = { MusicPlayback.enqueue(playlist.tracks); playlistMenu = false })
                GlassMenuItem(text = { Text("Rename") }, onClick = { rename = true; name = playlist.name; playlistMenu = false })
                GlassMenuItem(text = { Text("Delete playlist") }, onClick = { confirmDelete = true; playlistMenu = false })
            }
        }
    }
    }
    }
    if (create || rename) GlassConfirmDialog(onDismissRequest = { create = false; rename = false }, title = { Text(if (rename) "Rename playlist" else "Create playlist") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }) }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            MusicRepository.playlist(name, if (rename) playlist?.tracks.orEmpty() else selected.toList(), if (rename) playlist?.id else null); create = false; rename = false
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { create = false; rename = false }) { Text("Cancel") } })
    if (confirmDelete) GlassConfirmDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete playlist?") }, text = { Text("Your audio files will be kept.") }, confirmButton = { TextButton(onClick = { playlist?.let { MusicRepository.deletePlaylist(it.id) }; playlistId = null; confirmDelete = false }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    options?.let { track -> TrackOptions(track, onDismiss = { options = null }, onPlaylist = { addTracks = listOf(track.id); options = null }, onDownload = onDownload, extraActions = if (playlist != null && optionsIndex >= 0) listOf(
        "Move up in playlist" to { if (optionsIndex > 0) MusicRepository.playlist(playlist.name, playlist.tracks.toMutableList().apply { java.util.Collections.swap(this, optionsIndex, optionsIndex - 1) }, playlist.id); options = null },
        "Remove from playlist" to { MusicRepository.playlist(playlist.name, playlist.tracks.toMutableList().apply { removeAt(optionsIndex) }, playlist.id); options = null }
    ) else emptyList()) }
    addTracks?.let { ids -> PlaylistPicker(ids) { addTracks = null; selected = emptySet() } }
}
@Composable private fun MusicTrackRow(track: LocalTrack, number: Int? = null, selected: Boolean, selectionActive: Boolean, showCheckbox: Boolean, onSelect: () -> Unit, onPlay: () -> Unit, onMore: () -> Unit) {
    val currentFlow=remember {MusicPlayback.state.map {it.track?.id to it.playing}.distinctUntilChanged()}
    val current by currentFlow.collectAsStateWithLifecycle(initialValue=remember {MusicPlayback.state.value.let {it.track?.id to it.playing}})
    val active=current.first==track.id
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if(selected || active)MusicRed.copy(alpha=.10f) else Color.Transparent).heightIn(min=68.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(34.dp).background(if(active)MusicRed else Color.Transparent,RoundedCornerShape(50)))
            if(showCheckbox)Checkbox(selected,{onSelect()},modifier=Modifier.size(40.dp))
            Row(Modifier.weight(1f).combinedClickable(enabled=track.available,onClick={if(selectionActive)onSelect() else onPlay()},onLongClick=onSelect).padding(vertical=8.dp,horizontal=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                if(number!=null && !selectionActive) Box(Modifier.width(24.dp).height(48.dp),contentAlignment=Alignment.Center) {
                    if(active)PlayingNotation(current.second,Modifier.size(19.dp))
                    else Text(number.toString(),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } else Box(Modifier.size(50.dp)) {
                    TrackArtwork(track,Modifier.fillMaxSize())
                    if(active)Box(Modifier.matchParentSize().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha=.38f)),contentAlignment=Alignment.Center) {PlayingNotation(current.second,Modifier.size(21.dp),Color.White)}
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(track.title,fontSize=15.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(active)MusicRed else MaterialTheme.colorScheme.onSurface)
                    Text(if(!track.available) "File unavailable" else if(active) "${if(current.second) "Playing" else "Paused"} · ${track.artist}" else track.artist,fontSize=12.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            Text(if(track.durationMs>0)durationLabel(track.durationMs/1000) else "—",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick=onMore) {Icon(Icons.Default.MoreHoriz,"Options for ${track.title}",Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        HorizontalDivider(Modifier.padding(start=if(number!=null)44.dp else 70.dp),thickness=.5.dp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.07f))
    }
}
@Composable internal fun MusicEmpty(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.LibraryMusic, null, Modifier.size(36.dp), tint = MusicRed)
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
        Text(description, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun LibraryLayoutControl(layout: String, onChange: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.testTag("music-layout-control")) {
        Row {
            listOf("Grid" to Icons.Rounded.GridView, "Compact" to Icons.Rounded.ViewList).forEach { (value, icon) ->
                IconToggleButton(checked = layout == value, onCheckedChange = { onChange(value) }, modifier = Modifier.size(48.dp)) {
                    Icon(icon, "$value layout", Modifier.size(20.dp), tint = if (layout == value) MusicRed else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
@Composable internal fun MusicChoice(title: String, value: String, choices: List<String>, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)).clickable { expanded = true }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ExpandMore, "Choose $title", Modifier.size(20.dp))
    }
    if (expanded) GlassSheet(onDismissRequest = { expanded = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(choices.distinct()) { _, option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(option); expanded = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(option == value || option == value.removeSuffix("x").removeSuffix("s"), { onChange(option); expanded = false })
                        Text(option, Modifier.weight(1f).padding(end = 8.dp))
                    }
                }
            }
        }
    }
}
@Composable internal fun PlaylistPicker(ids: List<String>, onDismiss: () -> Unit) {
    val state by MusicRepository.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    GlassSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add to playlist", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (state.playlists.isEmpty()) item { Text("Create your first playlist below", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(state.playlists, key = { _, p -> p.id }, contentType = { _, _ -> "playlist" }) { _, p ->
                    MusicActionRow(p.name, Icons.Rounded.PlaylistPlay, { MusicRepository.addToPlaylist(p.id, ids); onDismiss() }, subtitle = "${p.tracks.size} tracks", forward = true)
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("New playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { MusicRepository.playlist(name, ids); onDismiss() }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Create and add") }
        }
    }
}
@Composable internal fun TrackOptions(track: LocalTrack, onDismiss: () -> Unit, onPlaylist: () -> Unit, onDownload: (String) -> Unit, extraActions: List<Pair<String, () -> Unit>> = emptyList()) {
    val downloads by DownloadStore.items.collectAsStateWithLifecycle()
    val state by MusicRepository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf("Actions") }
    val playback by MusicPlayback.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf(false) }
    BackHandler(section != "Actions") { section = "Actions" }
    GlassSheet(onDismissRequest = { if (section != "Actions") section = "Actions" else onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (section != "Actions") IconButton(onClick = { section = "Actions" }) { Icon(Icons.Rounded.ArrowBack, "Back to track actions") }
                else TrackArtwork(track, Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (section == "Actions") track.title else section, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (section == "Actions") track.artist else track.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close track actions", Modifier.size(20.dp)) }
            }
            AnimatedContent(section, modifier = Modifier.weight(1f, fill = false), transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "Track action page") { page ->
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                    if (page == "Actions") {
                        Surface(shape = MobileUi.card, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)) {
                            Column {
                                if (playback.track?.id != track.id) MusicActionRow("Play now", Icons.Rounded.PlayArrow, { MusicPlayback.play(listOf(track.id)); onDismiss() }, enabled = track.available)
                                MusicActionRow("Play next", Icons.Rounded.PlaylistPlay, { MusicPlayback.enqueue(listOf(track.id), true); onDismiss() }, enabled = track.available)
                                MusicActionRow("Add to queue", Icons.Rounded.QueueMusic, { MusicPlayback.enqueue(listOf(track.id)); onDismiss() }, enabled = track.available)
                                MusicActionRow("Add to playlist", Icons.Rounded.PlaylistAdd, onPlaylist, forward = true)
                                MusicActionRow(if (track.id in state.favorites) "Remove favorite" else "Add favorite", if (track.id in state.favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, { MusicRepository.favorite(track.id); onDismiss() })
                            }
                        }
                        if (extraActions.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Surface(shape = MobileUi.card, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)) {
                                Column { extraActions.forEach { (label, action) -> MusicActionRow(label, when {
                                    label == "Lyrics" -> Icons.Rounded.Lyrics
                                    label == "Sleep timer" -> Icons.Rounded.Schedule
                                    label == "Audio output" -> Icons.Rounded.DevicesOther
                                    label.startsWith("Skip backward") -> Icons.Rounded.FastRewind
                                    label.startsWith("Skip forward") -> Icons.Rounded.FastForward
                                    label.startsWith("Move") -> Icons.Rounded.ArrowUpward
                                    else -> Icons.Rounded.RemoveCircleOutline
                                }, action) } }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        MusicActionRow("Track information", Icons.Rounded.Info, { section = "Track information" }, forward = true)
                        if (track.downloadId != null && downloads.any { it.id == track.downloadId }) MusicActionRow("Open download details", Icons.Rounded.Download, { onDownload(track.downloadId); onDismiss() }, forward = true)
                        MusicActionRow("Manage file", Icons.Rounded.FolderOpen, { section = "Manage file" }, forward = true)
                    } else if (page == "Manage file") {
                        MusicActionRow("Open file location", Icons.Rounded.FolderOpen, { runCatching { context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra("android.provider.extra.INITIAL_URI", Uri.parse(track.uri))) }.onFailure { MusicRepository.message.value = it.message }; onDismiss() })
                        MusicActionRow("Remove from library", Icons.Rounded.LibraryMusic, { MusicRepository.remove(setOf(track.id)); onDismiss() }, subtitle = "Keep the audio file on your phone")
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
                        MusicActionRow("Delete audio file…", Icons.Rounded.DeleteOutline, { deleting = true }, subtitle = "Permanently delete the source file", destructive = true)
                    } else {
                        listOf("Title" to track.title, "Artist" to track.artist, "Album" to track.album, "File" to track.filename,
                            "Audio" to "${track.format} · ${if (track.bitrate > 0) "${track.bitrate / 1000} kbps" else "Bitrate unavailable"} · ${bytesLabel(track.bytes)}", "Location" to track.uri).forEach { (label, value) ->
                            if (value.isNotBlank()) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                androidx.compose.foundation.text.selection.SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (deleting) GlassConfirmDialog(onDismissRequest = { deleting = false }, title = { Text("Permanently delete this audio file?") }, text = { Text("${track.filename}\nThis deletes the source file, including an imported file when its provider allows deletion.") }, confirmButton = { TextButton(onClick = { scope.launch {
        runCatching { withContext(Dispatchers.IO) {
            val uri = Uri.parse(track.uri)
            check(context.contentResolver.delete(uri, null, null) > 0) { "Provider did not allow deletion. Delete it using your file manager." }
            MusicRepository.remove(setOf(track.id))
        } }.onFailure { MusicRepository.message.value = it.message }; deleting = false; onDismiss()
    } }) { Text("Delete file") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}
