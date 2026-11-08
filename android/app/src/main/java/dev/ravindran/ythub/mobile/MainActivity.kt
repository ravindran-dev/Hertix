@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.ravindran.ythub.mobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Crimson = MobileUi.accent
private val Success = Color(0xFF34D399)
private val PagePadding = MobileUi.page
internal val LocalMusicBottomPadding = staticCompositionLocalOf { 112.dp }
private val CardShape = MobileUi.card

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        enableEdgeToEdge()
        receiveShare(intent)
        setContent { YTHubApp() }
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !MusicRepository.state.value.settings.background) MusicPlayback.pauseIfConnected()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); receiveShare(intent) }
    private fun receiveShare(intent: Intent?) {
        if(intent?.getBooleanExtra("open_account",false)==true) {CloudBackupPolicy.openAccount.value=true;intent.removeExtra("open_account")}
        intent?.getStringExtra("play_download")?.let { MusicPlayback.downloadToPlay.value = it; intent.removeExtra("play_download") }
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link = Regex("https://[^\\s]+").find(text)?.value.orEmpty()
            if (link.isNotBlank()) DownloadStore.share(link)
        }
    }
}

@Composable private fun YTHubApp() {
    val musicReady by MusicRepository.ready.collectAsStateWithLifecycle()
    val preferences by DownloadStore.prefs.collectAsStateWithLifecycle()
    val jobs by DownloadStore.items.collectAsStateWithLifecycle()
    val musicMessage by MusicRepository.message.collectAsStateWithLifecycle()
    val playDownload by MusicPlayback.downloadToPlay.collectAsStateWithLifecycle()
    val playerError by MusicPlayback.error.collectAsStateWithLifecycle()
    val engine by DownloadStore.engine.collectAsStateWithLifecycle()
    val shared by DownloadStore.sharedUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val atmosphereLayer = rememberGraphicsLayer()
    val screenBackdrop = rememberGraphicsLayer()
    val sharedBlurPx=with(LocalDensity.current){4.dp.toPx()}
    DisposableEffect(screenBackdrop,sharedBlurPx) {
        screenBackdrop.scaleX = 2f; screenBackdrop.scaleY = 2f; screenBackdrop.pivotOffset = Offset.Zero
        if(Build.VERSION.SDK_INT>=31)screenBackdrop.renderEffect=BlurEffect(sharedBlurPx,sharedBlurPx,TileMode.Clamp)
        onDispose { }
    }
    val dockBackdrop = rememberDockGlassState()
    val popoverHost = rememberGlassPopoverHost()
    val popoverBackdrop = rememberGraphicsLayer()
    val popoverOpen = popoverHost.entry != null
    val playerPullOffset = remember { mutableFloatStateOf(0f) }
    val contentOrigin = Offset.Zero
    var page by rememberSaveable { mutableStateOf("Home") }
    var updatePrompt by remember { mutableStateOf<YTHubRelease?>(null) }
    LaunchedEffect(Unit) {
        val updates = context.getSharedPreferences("app_updates", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - updates.getLong("last_check", 0L) >= 24L * 60L * 60L * 1000L) {
            runCatching { AppUpdater.checkForUpdate() }.onSuccess { status ->
                updates.edit().putLong("last_check", now).apply()
                val available = (status as? AppUpdateStatus.Available)?.release
                if (available != null && now >= updates.getLong("snoozed_until", 0L)) updatePrompt = available
            }
        }
    }
    val openBackup by CloudBackupPolicy.openAccount.collectAsStateWithLifecycle()
    LaunchedEffect(openBackup) {if(openBackup){page="Account";CloudBackupPolicy.openAccount.value=false}}
    val dark = preferences.theme == "Dark" || (preferences.theme == "System" && isSystemInDarkTheme())
    DisposableEffect(dark) {
        val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        (context as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        onDispose { }
    }
    val scheme = if (dark) darkColorScheme(primary = Crimson, onPrimary = Color.White, secondary = Crimson, onSecondary = Color.White, secondaryContainer = Color(0xFF3A1420), onSecondaryContainer = Color.White, background = MobileUi.background, surface = MobileUi.surface,
        surfaceVariant = MobileUi.elevated, onSurface = Color(0xFFF8F8FA), onSurfaceVariant = Color(0xFF9C9CA4), outline = Color(0xFF353946))
    else lightColorScheme(primary = Color(0xFFC9002C), onPrimary = Color.White, secondary = Crimson, secondaryContainer = Color(0xFFFFDDE4), onSecondaryContainer = Color(0xFF6C1027), background = Color(0xFFF6F5F3), surface = Color.White,
        surfaceVariant = Color(0xFFEEECEF), onSurface = Color(0xFF171923), onSurfaceVariant = Color(0xFF66666F), outline = Color(0xFFA8ABB5))
    val pageState = rememberSaveableStateHolder()
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var librarySearch by remember { mutableStateOf(false) }
    var youtubeSearchRequested by remember { mutableStateOf(false) }
    var requestedLibraryCategory by remember { mutableStateOf<String?>(null) }
    var librarySearchActive by rememberSaveable { mutableStateOf(false) }
    var libraryRootRequested by remember { mutableStateOf(false) }
    var libraryDetailActive by remember { mutableStateOf(false) }
    var phoneImportActive by remember { mutableStateOf(false) }
    var requestedPlaylist by remember { mutableStateOf<String?>(null) }
    var navigatingBack by remember { mutableStateOf(false) }
    var playerOrigin by rememberSaveable { mutableStateOf("Library") }
    var settingsOrigin by rememberSaveable { mutableStateOf("Settings") }
    var secondaryOrigin by rememberSaveable { mutableStateOf("Settings") }
    fun openPlayer() { navigatingBack = false; if (page != "Player") playerOrigin = if (page == "Library" && librarySearchActive) "Search" else page; page = "Player" }
    fun closePlayer() { navigatingBack = true; page = if (playerOrigin == "Search") "Library" else playerOrigin }
    fun openSecondary(destination: String) { navigatingBack = false; secondaryOrigin = page; page = destination }
    fun openPlayerSettings() { navigatingBack = false; settingsOrigin = page; page = "Player Settings" }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var saveDownloadedPlaylist by rememberSaveable { mutableStateOf(true) }
    var downloadedPlaylistName by rememberSaveable { mutableStateOf("") }
    var phoneMusicRequested by remember { mutableStateOf(false) }
    LaunchedEffect(media?.url) {
        saveDownloadedPlaylist = MusicRepository.state.value.settings.downloadPlaylists
        downloadedPlaylistName = media?.title.orEmpty()
    }
    fun openLibrary() {
        navigatingBack = false
        detail = null; media = null; librarySearch = false; librarySearchActive = false; requestedPlaylist = null
        libraryRootRequested = true; page = "Library"
    }
    fun openPhoneMusic() {
        navigatingBack = false
        detail = null; media = null; librarySearch = false; librarySearchActive = false; requestedPlaylist = null
        libraryRootRequested = false; phoneMusicRequested = true; page = "Library"
    }
    var url by rememberSaveable { mutableStateOf("") }
    var audio by rememberSaveable { mutableStateOf(true) }
    var quality by rememberSaveable { mutableStateOf(preferences.audioQuality) }
    var playlist by rememberSaveable { mutableStateOf(true) }
    var analyzing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); notice = null } }
    var qualitySheet by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val phoneFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { runCatching { MusicRepository.importAudio(uris) }.onSuccess { openLibrary() }.onFailure { error = it.message } }
    }
    LaunchedEffect(playDownload) {
        playDownload?.let { id ->
            val ids = runCatching { MusicRepository.downloadedTracks(id) }.getOrDefault(emptyList())
            if (ids.isNotEmpty()) { MusicPlayback.play(ids); openPlayer(); detail = null } else error = "Audio unavailable. Open Library to rescan or extract an archived playlist."
            MusicPlayback.downloadToPlay.value = null
        }
    }
    LaunchedEffect(musicMessage) { if (musicMessage != null) { error = musicMessage; MusicRepository.message.value = null } }
    LaunchedEffect(playerError) { if (playerError != null) { error = playerError; MusicPlayback.error.value = null } }
    LaunchedEffect(Unit) { if (MusicRepository.state.value.settings.restoreTrack) MusicPlayback.restore() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            DownloadStore.savePrefs(DownloadStore.prefs.value.copy(folderUri = uri.toString(), folderName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected folder"))
        }.onFailure { error = "Unable to use this folder: ${it.message}" }
    }
    val cookiesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val target = File(context.filesDir, "cookies.txt")
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } } ?: error("Could not read cookies")
                check(target.length() <= 2 * 1024 * 1024 && target.readText().contains("Netscape")) { "Choose a Netscape-format cookies.txt file (up to 2 MB)." }
                DownloadStore.savePrefs(DownloadStore.prefs.value.copy(cookiesPath = target.absolutePath))
            } }.onSuccess { notice = "Cookies imported into private app storage" }.onFailure { error = it.message }
        }
    }
    LaunchedEffect(shared) { if (shared.isNotBlank()) { url = shared; page = "Home"; media = null; DownloadStore.consumeShare() } }
    fun act(action: () -> Unit) { runCatching(action).onFailure { error = it.message ?: "This action failed." } }
    fun startDownload() {
        val analyzed = media ?: return
        act {
            DownloadStore.enqueue(analyzed, audio, quality, if (audio && analyzed.playlist) saveDownloadedPlaylist else false, if (audio && analyzed.playlist && saveDownloadedPlaylist) downloadedPlaylistName else null)
            if (Build.VERSION.SDK_INT >= 33 && preferences.notifications) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            media = null; page = "Downloads"; notice = if (preferences.autoStart) "Added to your download queue" else "Added paused · tap resume to start"
        }
    }
    val pageScroll = remember(page, detail, media?.url) { PageScrollState() }
    BackHandler(enabled = detail != null || media != null || page != "Home") {
        navigatingBack = true
        if (detail != null) detail = null else if (media != null) media = null else page = when (page) { "Player Settings" -> settingsOrigin; "Player" -> if (playerOrigin == "Search") "Library" else playerOrigin; "Storage", "History", "Logs", "Account", "About", "App Updates" -> secondaryOrigin; "Cloud Library" -> secondaryOrigin; "Downloads", "Library", "Explore", "Settings" -> "Home"; else -> "Settings" }
    }
    MaterialTheme(colorScheme = scheme, typography = musicTypography()) {
        CompositionLocalProvider(LocalPageScroll provides pageScroll, LocalDockGlass provides dockBackdrop, LocalAccountAction provides {if(page!="Account")openSecondary("Account")}, LocalAtmosphereLayer provides atmosphereLayer, LocalPlayerPullOffset provides playerPullOffset, LocalGlassScene provides GlassScene(screenBackdrop, contentOrigin), LocalPopoverGlassBackdrop provides if(popoverOpen) popoverBackdrop else null, LocalGlassPopoverHost provides popoverHost) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Box(Modifier.fillMaxSize()) {
            ScreenAtmosphere(if (preferences.onboardingDone) page else "", atmosphereLayer, screenBackdrop)
            if (!preferences.onboardingDone) {
                Onboarding(engine) { DownloadStore.savePrefs(preferences.copy(onboardingDone = true)) }
            } else {
                Scaffold(modifier = Modifier.semantics { if (popoverHost.entry != null) hideFromAccessibility() }.capturePopoverGlass(atmosphereLayer, popoverBackdrop, popoverOpen), containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !keyboardOpen && page != "Player" && !phoneImportActive,
                        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180)) { it / 5 },
                        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100)) + androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(150)) { it / 5 }
                    ) {
                    FloatingPlaybackDock(
                        destination = if (page == "Library" && (librarySearchActive || librarySearch)) "Search" else page,
                        onExplore = { navigatingBack = false; page = "Explore"; detail = null; media = null },
                        onHome = { navigatingBack = page != "Home"; page = "Home"; detail = null; media = null }, onPlayer = { openPlayer() },
                        onLibrary = ::openLibrary,
                        onSearch = { navigatingBack = false; libraryRootRequested = false; page = "Library"; detail = null; media = null; librarySearch = true },
                        dark = dark, dockBackdrop = dockBackdrop)
                    }
                }, topBar = {
                    if ((page == "Player" || page == "Library" && libraryDetailActive) && detail == null && media == null) Spacer(Modifier.statusBarsPadding()) else Row(Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal = PagePadding, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (media != null || detail != null || page !in listOf("Home", "Downloads", "Player", "Library", "Explore", "Account", "Settings")) {
                            GlassControlSurface(Modifier.size(48.dp), dark) { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Go back", { navigatingBack = true; if (detail != null) detail = null else if (media != null) media = null else page = if (page == "Player Settings") settingsOrigin else secondaryOrigin }) }
                            Spacer(Modifier.width(12.dp))
                            ScrollingHeaderTitle(if (detail != null) "Download details" else if (media != null) if (media!!.playlist) "Playlist details" else "Media details" else if(page=="Cloud Library") "Online library" else page, Modifier.weight(1f))
                            if (media != null || detail != null) {
                                var contextMenu by remember { mutableStateOf(false) }
                                val item = jobs.find { it.id == detail }
                                Box {
                                    PairedGlassActions(onAdd = ::openPhoneMusic, onMore = { contextMenu = true }, addLabel = "Phone music", moreLabel = "Contextual options")
                                    if (contextMenu) GlassActionPopover("Actions", { contextMenu = false }) {
                                        GlassMenuItem(text = { Text("Import music from phone") }, onClick = { contextMenu = false; openPhoneMusic() })
                                        if (media != null) {
                                            GlassMenuItem(text = { Text("Download") }, onClick = { contextMenu = false; startDownload() })
                                            GlassMenuItem(text = { Text("Copy source URL") }, onClick = { copyText(context, media!!.url); contextMenu = false })
                                        }
                                        if (item != null) {
                                            if (item.state in listOf("Failed", "Paused", "Interrupted")) GlassMenuItem(text = { Text("Resume / retry") }, onClick = { act { DownloadStore.resume(item.id) }; contextMenu = false })
                                            if (item.uris.isNotEmpty()) GlassMenuItem(text = { Text("Open file") }, onClick = { act { openMedia(context, item.uris.first(), item.mime) }; contextMenu = false })
                                            if (item.zipUri.isNotBlank()) GlassMenuItem(text = { Text("Open playlist ZIP") }, onClick = { act { openMedia(context, item.zipUri, "application/zip") }; contextMenu = false })
                                            if (item.terminal) GlassMenuItem(text = { Text("Remove history entry") }, onClick = { act { DownloadStore.remove(item.id); detail = null }; contextMenu = false })
                                        }
                                    }
                                }
                            }
                        } else {
                            ScrollingHeaderTitle(if (page == "Home") "YTHub" else if (page == "Library" && librarySearchActive) "Search" else page, Modifier.weight(1f), brand = page == "Home")
                            if (page == "Library" && !libraryDetailActive && !librarySearchActive && !librarySearch) {
                                LibrarySourceToggle {
                                    navigatingBack = false
                                    secondaryOrigin = "Library"
                                    page = if (CloudAccount.state.value.userId == null) "Account" else "Cloud Library"
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            var appMenu by remember { mutableStateOf(false) }
                            Box {
                            PairedGlassActions(onAdd = ::openPhoneMusic, onMore = { appMenu = true },
                                addLabel = "Phone music", moreLabel = "More")
                            if (appMenu) GlassPopover({ appMenu = false }) {
                                GlassMenuBlock(backdrop = LocalPopoverGlassBackdrop.current ?: screenBackdrop, contentOrigin = contentOrigin) {
                                    listOf(
                                        "Import music from phone" to Icons.Default.AddToPhotos,
                                        "Downloads" to Icons.Default.Download,
                                        "Cloud Sync" to Icons.Default.CloudSync,
                                        "Settings" to Icons.Default.Settings,
                                        "History" to Icons.Default.History,
                                        "App updates" to Icons.Default.SystemUpdate,
                                        "About YTHub" to Icons.Default.Info
                                    ).forEach { (destination, icon) ->
                                        MusicActionRow(destination, icon, {
                                            appMenu = false
                                            when(destination) {
                                                "Import music from phone" -> openPhoneMusic()
                                                "Downloads" -> page = "Downloads"
                                                "Cloud Sync" -> openSecondary(if(CloudAccount.state.value.userId == null) "Account" else "Cloud Library")
                                                "About YTHub" -> openSecondary("About")
                                                "App updates" -> openSecondary("App Updates")
                                                else -> openSecondary(destination)
                                            }
                                        })
                                    }
                                }
                            }
                            }
                        }
                    }
                }) { inset ->
                    val reserveBottom = if (page == "Player" || page == "Player Settings" || keyboardOpen) inset.calculateBottomPadding() else 0.dp
                    val appliedInsets = PaddingValues(top = inset.calculateTopPadding(), bottom = reserveBottom)
                    CompositionLocalProvider(LocalMusicBottomPadding provides if (reserveBottom > 0.dp) 0.dp else inset.calculateBottomPadding()) {
                    AnimatedContent(targetState = when { detail != null -> "detail"; media != null -> "media"; else -> page }, transitionSpec = { MobileMotion.navigation(initialState, targetState, navigatingBack).using(null) }, label = "Navigation", modifier = Modifier
                        .padding(appliedInsets).consumeWindowInsets(appliedInsets).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).imePadding()
                        .fillMaxSize().nestedScroll(pageScroll).captureDockGlass(dockBackdrop, atmosphereLayer, enabled = !keyboardOpen && page != "Player")) { destination ->
                        pageState.SaveableStateProvider(destination) {
                        when (destination) {
                            "Home" -> HomeScreen(onDownloads = { navigatingBack = false; page = "Downloads" }, onLibrary = ::openLibrary, onPlayer = { openPlayer() }, onOpenPlaylist = { navigatingBack = false; requestedPlaylist = it; page = "Library" })
                            "Explore" -> ExploreScreen(
                                url = url, onUrl = { url = it }, audio = audio,
                                onAudio = { audio = it; quality = if (it) preferences.audioQuality else preferences.videoQuality },
                                quality = quality, onQuality = { qualitySheet = true }, playlistDownload = playlist, onPlaylistDownload = { playlist = it },
                                preferences = preferences, engineReady = engine.contains("ready"), busy = analyzing,
                                onAnalyze = { scope.launch {
                                    analyzing = true
                                    runCatching { DownloadStore.analyze(url, playlist) }.onSuccess { media = it }.onFailure { error = it.message ?: "Unable to analyze this URL" }
                                    analyzing = false
                                } }, activeDownloads = jobs.count { !it.terminal },
                                onStorage = { openSecondary("Storage") },
                                onYouTubeSearch = { navigatingBack = false; page = "Library"; librarySearch = false; youtubeSearchRequested = true },
                                onLibraryCategory = { category -> navigatingBack = false; requestedLibraryCategory = category; page = "Library" },
                                onPlaylist = { id -> navigatingBack = false; requestedPlaylist = id; page = "Library" },
                                onDownloads = { navigatingBack = false; page = "Downloads" }
                            )
                            "media" -> media?.let { MediaScreen(it, audio, { audio = it; quality = if (it) preferences.audioQuality else preferences.videoQuality }, quality, { qualitySheet = true }, preferences, ::startDownload, saveDownloadedPlaylist, { saveDownloadedPlaylist = it }, downloadedPlaylistName, { downloadedPlaylistName = it }) }
                            "Downloads" -> DownloadsScreen(jobs, { detail = it }, { id -> act { DownloadStore.pause(id) } }, { id -> act { DownloadStore.resume(id) } }, { confirmCancel = it }, { page = "Home" },
                                onPlay = { MusicPlayback.downloadToPlay.value = it }, onAdd = { id -> scope.launch {
                                    runCatching { MusicRepository.downloadedTracks(id) }.onSuccess { notice = if (it.isEmpty()) "No accessible audio files found" else "Added to your music library" }.onFailure { error = it.message }
                                } })
                            "Library" -> MusicLibraryScreen(onDetailChanged = { libraryDetailActive = it }, onPhoneImportChanged = { phoneImportActive = it }, onPlayer = { openPlayer() }, onDownload = { navigatingBack = false; detail = it }, rootRequested = libraryRootRequested, onRootConsumed = { libraryRootRequested = false }, requestedPlaylist = requestedPlaylist, onPlaylistConsumed = { requestedPlaylist = null }, requestedCategory = requestedLibraryCategory, onCategoryConsumed = { requestedLibraryCategory = null }, onOnline = { secondaryOrigin = "Library"; page = if(CloudAccount.state.value.userId==null) "Account" else "Cloud Library" }, onSearchChanged = { librarySearchActive = it }, searchRequested = librarySearch, youtubeSearchRequested = youtubeSearchRequested, onSearchConsumed = { librarySearch = false }, onYoutubeSearchConsumed = { youtubeSearchRequested = false }, phoneMusicRequested = phoneMusicRequested, onPhoneMusicConsumed = { phoneMusicRequested = false })
                            "Player" -> {
                                val playerPopupProgress by transition.animateFloat(
                                    transitionSpec = { spring(dampingRatio = .9f, stiffness = 700f, visibilityThreshold = .002f) },
                                    label = "Player popup to dock"
                                ) { phase -> if (phase == EnterExitState.Visible) 1f else 0f }
                                NowPlayingScreen(onLibrary = ::openLibrary, onSettings = { openPlayerSettings() }, onDownload = { navigatingBack = false; detail = it }, onMinimize = ::closePlayer, onPhoneMusic = ::openPhoneMusic, source = playerOrigin, motionProgress = playerPopupProgress)
                            }
                            "Player Settings" -> PlayerSettingsScreen(onStorage = { openSecondary("Storage") })
                            "Account" -> AccountScreen(onCloudLibrary = { secondaryOrigin = "Account"; page = "Cloud Library" }, onSettings = { openSecondary("Settings") }, onPlayerSettings = { openPlayerSettings() }, onDownloads = { page = "Downloads" }, onAbout = { openSecondary("About") })
                            "Cloud Library" -> CloudLibraryScreen()
                            "History" -> HistoryScreen(jobs, { detail = it }, { page = "Home" })
                            "detail" -> jobs.find { it.id == detail }?.let { item -> DownloadDetail(item,
                                onPause = { act { DownloadStore.pause(item.id) } }, onResume = { act { DownloadStore.resume(item.id) } }, onCancel = { confirmCancel = item.id },
                                onCopy = { copyText(context, item.media.url); notice = "URL copied" },
                                onOpen = { if (item.audio && item.state == "Completed") scope.launch {
                                    val ids = runCatching { MusicRepository.downloadedTracks(item.id) }.getOrDefault(emptyList())
                                    if (ids.isNotEmpty()) { MusicPlayback.play(ids); detail = null; page = "Player" } else error = "No accessible audio files. If this download is archived, use Extract and import playlist."
                                } else act { openMedia(context, item.uris.firstOrNull() ?: item.zipUri, item.mime) } },
                                onExtract = { scope.launch { runCatching { MusicRepository.extractPlaylist(Uri.parse(item.zipUri), item.media.title) }.onSuccess { detail = null; page = "Library" }.onFailure { error = it.message } } },
                                onZip = { act { openMedia(context, item.zipUri, "application/zip") } },
                                onFolder = { act { openFolder(context, item.options.folderUri) } },
                                onRemove = { act { DownloadStore.remove(item.id); detail = null } }) }
                            "Settings" -> SettingsScreen(preferences, engine, onFolder = { folderPicker.launch(null) }, onStorage = { openSecondary("Storage") },
                                onPlayer = { openPlayerSettings() }, onHistory = { openSecondary("History") }, onCookies = { cookiesPicker.launch(arrayOf("text/plain", "application/octet-stream")) }, onLogs = { openSecondary("Logs") },
                                onUpdate = { scope.launch { notice = "Checking for an engine update…"; runCatching { DownloadStore.updateEngine() }.onSuccess { notice = it }.onFailure { error = it.message } } })
                            "Storage" -> StorageScreen(jobs, preferences, onFolder = { folderPicker.launch(null) }, onClear = { scope.launch { runCatching { withContext(Dispatchers.IO) { DownloadStore.clearTemporary() } }.onSuccess { notice = "Cleared ${bytesLabel(it)} of temporary files" }.onFailure { error = it.message } } })
                            "Logs" -> LogScreen(jobs)
                            "App Updates" -> AppUpdateCheckerScreen()
                            "About" -> AboutScreen(onDiagnostics = { openSecondary("Logs") }, onUpdates = { openSecondary("App Updates") }, onBack = { navigatingBack = true; page = secondaryOrigin })
                        }
                        }
                    }
                    }
                }
            }
            GlassPopoverOverlay(popoverHost)
            updatePrompt?.let { available ->
                AppUpdateAvailableDialog(available, onRemindLater = {
                    context.getSharedPreferences("app_updates", android.content.Context.MODE_PRIVATE).edit()
                        .putLong("snoozed_until", System.currentTimeMillis() + 24L * 60L * 60L * 1000L).apply()
                    updatePrompt = null
                })
            }
            if (qualitySheet) GlassSheet(onDismissRequest = { qualitySheet = false }) {
                Column(Modifier.fillMaxWidth().padding(PagePadding).navigationBarsPadding()) {
                    Text(if (audio) "MP3 audio quality" else "Video quality", style = MaterialTheme.typography.headlineSmall)
                    Text(if (audio) "Higher bitrate uses more storage." else "The engine uses the best available format up to your limit.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    val choices = if (audio) listOf("320", "256", "192", "128", "best") else listOf("best", "2160", "1440", "1080", "720", "480")
                    choices.forEach { option -> Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { quality = option; qualitySheet = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option == quality, onClick = { quality = option; qualitySheet = false })
                        Text(qualityLabel(audio, option), modifier = Modifier.padding(start = 8.dp))
                    } }
                }
            }
            if (error != null) GlassConfirmDialog(onDismissRequest = { error = null }, icon = { Icon(Icons.Default.ErrorOutline, null, tint = Crimson) }, title = { Text("Something needs attention") },
                text = { Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) { Text(error!!) } }, confirmButton = { TextButton(onClick = { error = null }) { Text("Got it") } })
            if (confirmCancel != null) GlassConfirmDialog(onDismissRequest = { confirmCancel = null }, title = { Text("Cancel this download?") },
                text = { Text("Downloaded parts are kept so you can retry later.") }, dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("Keep downloading") } },
                confirmButton = { TextButton(onClick = { act { DownloadStore.cancel(confirmCancel!!) }; confirmCancel = null }) { Text("Cancel download") } })
            androidx.compose.animation.AnimatedVisibility(visible = !musicReady,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)), modifier = Modifier.matchParentSize()) {
                StartupScreen(musicReady) {}
            }
        }
    }
    }
    }
}

@Composable private fun Onboarding(engine: String, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(100.dp).background(Brush.radialGradient(listOf(Crimson.copy(alpha = .22f), Color.Transparent)), CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ythub_logo), "YTHub play and download logo", Modifier.size(76.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Your media.\nYour way.", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("Welcome to YTHub", fontSize = 18.sp, modifier = Modifier.padding(top = 20.dp))
        Text("Download. Convert. Manage.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { listOf("MP3", "MP4", "Playlists", "ZIP").forEach { BadgeText(it) } }
        Spacer(Modifier.height(20.dp))
        Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (engine.contains("ready")) Icons.Default.CheckCircle else Icons.Default.Tune, null, tint = if (engine.contains("ready")) Success else Crimson); Text(engine, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Get started", Icons.Default.ArrowForward, onStart, enabled = engine.contains("ready"))
        Text("Your YouTube Media Hub", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
    }
}

@Composable private fun HomeScreen(onDownloads: () -> Unit, onLibrary: () -> Unit, onPlayer: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val library by MusicRepository.state.collectAsStateWithLifecycle()
    val recentDownloads = remember(library.tracks) { library.tracks.filter { it.downloadId != null }.sortedByDescending { it.added }.take(3) }
    val recentPlayed = remember(library.tracks, library.history) { library.tracks.filter { it.id in library.history }.sortedByDescending { library.history[it.id]?.lastPlayed }.take(3) }
    Column(Modifier.fillMaxSize().testTag("home-page-scroll").verticalScroll(rememberScrollState()).padding(horizontal = PagePadding).padding(top = 8.dp, bottom = LocalMusicBottomPadding.current + 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MusicRecentList("Recently Downloaded", recentDownloads, "Your downloaded songs appear here.", onDownloads) { MusicPlayback.play(listOf(it.id)); onPlayer() }
        MusicRecentList("Recently Played", recentPlayed, "Your recent listens appear here.", onLibrary) { MusicPlayback.play(listOf(it.id)); onPlayer() }
        Text("Playlists", style = MaterialTheme.typography.titleMedium)
        library.playlists.take(4).forEach { PlaylistArtworkRow(it, library.tracks) { onOpenPlaylist(it.id) } }
    }
}

@Composable internal fun MediaScreen(media: MediaInfo, audio: Boolean, onAudio: (Boolean) -> Unit, quality: String, onQuality: () -> Unit, prefs: Preferences, onDownload: () -> Unit,
    saveAsPlaylist: Boolean = false, onSaveAsPlaylist: (Boolean) -> Unit = {}, playlistName: String = media.title, onPlaylistName: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = PagePadding, end = PagePadding, top = PagePadding, bottom = LocalMusicBottomPadding.current + PagePadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Thumbnail(media.thumbnail, Modifier.size(width = 96.dp, height = 72.dp).clip(CardShape))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(media.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (media.channel.isNotBlank()) Text(media.channel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (media.playlist) "${media.count} items · ${media.entries.size} loaded" else durationLabel(media.duration), style = MaterialTheme.typography.labelSmall)
                    }
                }
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less details" else "Full details") }
                if (expanded) { Text(media.title); Text(media.url, style = MaterialTheme.typography.bodySmall) }
            }
            item { FormatSelector(audio, onAudio) }
            item { ChoiceRow("Quality", qualityLabel(audio, quality), Icons.Default.HighQuality, onQuality) }
            if (media.qualities.isNotEmpty()) item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { media.qualities.forEach { BadgeText("${it}p") } } }
            if (!media.playlist) item { PrimaryButton(if (audio) "Download audio" else "Download video", Icons.Default.Download, onDownload) }
            if (media.playlist) {
                item { CardBlock { ToggleRow("Preserve order", "Keep the original playlist sequence", prefs.preserveOrder) { DownloadStore.savePrefs(prefs.copy(preserveOrder = it)) }; ToggleRow("Create ZIP", "Archive after the downloads finish", prefs.autoZip) { DownloadStore.savePrefs(prefs.copy(autoZip = it)) } } }
                if (audio) item {
                    PlaylistDownloadOptions(saveAsPlaylist, onSaveAsPlaylist, playlistName, onPlaylistName)
                }
                item {
                    PrimaryButton("Download playlist", Icons.Default.Download, onDownload,
                        enabled = !audio || !saveAsPlaylist || playlistName.isNotBlank())
                }
                item { Label("PLAYLIST ITEMS") }
                items(media.entries.withIndex().toList(), key = { "${it.index}:${it.value.url}" }) { (index, entry) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d".format(index + 1), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp))
                        Thumbnail(entry.thumbnail, Modifier.width(72.dp).height(48.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp); if (entry.duration > 0) Text(durationLabel(entry.duration), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable internal fun PlaylistDownloadOptions(enabled: Boolean, onEnabled: (Boolean) -> Unit, name: String, onName: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("playlist-download-options"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleRow("Create a YTHub playlist", "Add these songs to your offline library", enabled, onEnabled)
        if (enabled) {
            OutlinedTextField(name, onName, label = { Text("Playlist name") }, singleLine = true,
                isError = name.isBlank(), supportingText = { Text(if (name.isBlank()) "Enter a playlist name" else "Keeps YouTube song order. Songs remain available offline, including with ZIP downloads.") },
                modifier = Modifier.fillMaxWidth().testTag("download-playlist-name"))
        }
    }
}

@Composable private fun DownloadsScreen(jobs: List<DownloadItem>, onDetail: (String) -> Unit, onPause: (String) -> Unit, onResume: (String) -> Unit, onCancel: (String) -> Unit, onHome: () -> Unit, onPlay: (String) -> Unit, onAdd: (String) -> Unit) {
    val music by MusicRepository.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("Completed") }
    val filtered = jobs.filter { when (filter) { "Completed" -> it.state == "Completed"; "Failed" -> it.state in listOf("Failed", "Cancelled"); else -> !it.terminal } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = LocalMusicBottomPadding.current + 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            GlassControlSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tintAlpha = .2f) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(Crimson.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Download, null, tint = Crimson, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 11.dp)) {
                        Text("Your downloads", style = MaterialTheme.typography.titleSmall)
                        Text("${jobs.count { !it.terminal }} active  ·  ${jobs.count { it.state == "Completed" }} completed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { FilterTabs(listOf("Active", "Completed", "Failed"), filter) { filter = it } }
        if (filtered.isEmpty()) item { Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) { EmptyState(if (filter == "Active") "Your queue is clear" else "No ${filter.lowercase()} downloads", if (filter == "Active") "New downloads and their progress will appear here." else "Start from Explore or YouTube search to add media.", onHome) } }
        else items(filtered, key = { it.id }, contentType = { "download" }) { item -> DownloadCard(item, { onDetail(item.id) }, { onPause(item.id) }, { onResume(item.id) }, { onCancel(item.id) }, onPlay = { onPlay(item.id) }, onAdd = if (music.tracks.any { it.downloadId == item.id }) null else { { onAdd(item.id) } }) }
    }
}

@Composable private fun DownloadCard(item: DownloadItem, onDetail: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onPlay: (() -> Unit)? = null, onAdd: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val statusColor = when (item.state) { "Completed" -> Success; "Processing", "Exporting", "Zipping" -> Color(0xFFFFBD66); "Paused", "Interrupted" -> MaterialTheme.colorScheme.onSurfaceVariant; "Failed", "Cancelled" -> MaterialTheme.colorScheme.error; else -> Crimson }
    GlassControlSurface(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).glassTouch(onDetail), shape = RoundedCornerShape(22.dp), flexible = false, tintAlpha = .25f) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Thumbnail(item.media.thumbnail, Modifier.fillMaxSize())
                    Surface(Modifier.align(Alignment.BottomStart).padding(4.dp), shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = .78f)) {
                        Icon(if (item.audio) Icons.Default.MusicNote else Icons.Default.Videocam, null, Modifier.padding(horizontal = 5.dp, vertical = 3.dp).size(12.dp), tint = Color.White)
                    }
                }
                Column(Modifier.padding(start = 11.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.media.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                    Text("${if (item.audio) "Audio · MP3" else "Video · MP4"}  ·  ${qualityLabel(item.audio, item.quality)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.state == "Completed" && item.audio && item.uris.isNotEmpty() && onPlay != null) IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "Play completed audio", tint = Crimson) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, "Download options") }
                    if (menu) GlassActionPopover("Download actions", { menu = false }) {
                        GlassMenuItem(text = { Text("Details") }, onClick = { menu = false; onDetail() })
                        if (!item.terminal) {
                            GlassMenuItem(text = { Text(if (item.state in listOf("Paused", "Interrupted")) "Resume" else "Pause") }, onClick = { menu = false; if (item.state in listOf("Paused", "Interrupted")) onResume() else onPause() })
                            GlassMenuItem(text = { Text("Cancel download") }, onClick = { menu = false; onCancel() })
                        } else if (item.state != "Completed") GlassMenuItem(text = { Text("Retry") }, onClick = { menu = false; onResume() })
                        if (item.state == "Completed" && item.audio && item.uris.isNotEmpty() && onAdd != null) GlassMenuItem(text = { Text("Add to library") }, onClick = { menu = false; onAdd() })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DownloadMotionStatus(item.state, statusColor)
                Spacer(Modifier.weight(1f))
                Text(if (item.state == "Completed") bytesLabel(item.bytes) else if (item.terminal) item.state else "${item.progress.toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!item.terminal) {
                DownloadMotionProgress(item, Modifier.fillMaxWidth().height(5.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (item.media.playlist) "${item.completedCount} of ${item.media.count} songs" else item.line.ifBlank { "Working on your download" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    if (item.eta > 0) Text("ETA ${durationLabel(item.eta)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.error.isNotBlank()) Text(item.error, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun HistoryScreen(jobs: List<DownloadItem>, onDetail: (String) -> Unit, onHome: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val filtered = jobs.filter { it.terminal && it.media.title.contains(search, true) && when (filter) { "Audio" -> it.audio; "Video" -> !it.audio; "Playlists" -> it.media.playlist; else -> true } }
    Column(Modifier.fillMaxSize().padding(horizontal = PagePadding).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            placeholder = { Text("Search downloads", maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Crimson) },
            trailingIcon = if (search.isNotEmpty()) ({ IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "Clear search") } }) else null,
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .62f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .48f),
                focusedBorderColor = Crimson.copy(alpha = .52f),
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
            )
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} ${if (filtered.size == 1) "item" else "items"}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(6.dp))
            Text("in download history", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilterTabs(listOf("All", "Audio", "Video", "Playlists"), filter) { filter = it }
        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (search.isNotBlank()) "No matching downloads" else if (filter == "All") "No download history yet" else "No ${filter.lowercase()} downloads",
                    if (search.isNotBlank()) "Try a different title or clear your search." else "Completed and stopped downloads will be listed here.",
                    onHome
                )
            }
        } else LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = LocalMusicBottomPadding.current + 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.id }, contentType = { "history-item" }) { item ->
                GlassControlSurface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { onDetail(item.id) },
                    shape = RoundedCornerShape(20.dp), tintAlpha = .18f
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Thumbnail(item.media.thumbnail, Modifier.size(56.dp).clip(RoundedCornerShape(13.dp)))
                            Surface(
                                Modifier.align(Alignment.BottomEnd).padding(3.dp),
                                shape = RoundedCornerShape(5.dp), color = Color.Black.copy(alpha = .76f)
                            ) {
                                Text(
                                    when { item.media.playlist -> "LIST"; item.audio -> "AUDIO"; else -> "VIDEO" },
                                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    fontSize = 8.sp, lineHeight = 10.sp, color = Color.White
                                )
                            }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.media.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${bytesLabel(item.bytes)} · ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(item.created))}",
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                item.state,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.state == "Completed") Success else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.ChevronRight, "Open download details", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun DownloadDetail(item: DownloadItem, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onCopy: () -> Unit, onOpen: () -> Unit, onExtract: () -> Unit, onZip: () -> Unit, onFolder: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding).padding(bottom = LocalMusicBottomPadding.current + 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(item.media.thumbnail, Modifier.size(64.dp).clip(CardShape))
            Text(item.media.title, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
        }
        if (item.state == "Completed") Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Success); Text(if (item.media.playlist) "Playlist complete" else "Download complete", color = Success, modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold) }
        if (!item.terminal) {
            DownloadMotionProgress(item, Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${item.progress.toInt()}%")
                if (item.eta > 0) Text("ETA ${durationLabel(item.eta)}")
            }
        }
        if (item.line.isNotBlank()) Text(item.line, style = MaterialTheme.typography.bodySmall)
        if (item.state !in listOf("Paused", "Interrupted") && !item.terminal) FlowRow {
            TextButton(onClick = onPause) { Text("Pause") }
            TextButton(onClick = onCancel) { Text("Cancel download") }
        }
        CardBlock {
            DetailRow("Format", if (item.audio) "MP3 audio" else "Video")
            DetailRow("Quality", qualityLabel(item.audio, item.quality))
            DetailRow("File size", if (item.bytes > 0) bytesLabel(item.bytes) else "Available after download")
            DetailRow("Destination", item.options.folderName)
            DetailRow("State", item.state)
            if (item.media.playlist) DetailRow("Playlist results", "${item.completedCount} downloaded · ${item.failedCount} failed")
            DetailRow("Source URL", item.media.url)
        }
        if (item.error.isNotBlank()) CardBlock { Label("DOWNLOAD ERROR"); Text(item.error, color = Crimson, fontSize = 13.sp) }
        if (item.zipUri.isNotBlank()) OutlinedButton(onClick = onExtract, modifier = Modifier.fillMaxWidth()) { Text("Extract and import playlist") }
        if (item.state == "Completed" && item.uris.isNotEmpty()) PrimaryButton(if (item.audio) "Play now" else "Open video", Icons.Default.PlayArrow, onOpen)
        if (item.zipUri.isNotBlank()) PrimaryButton("Open ZIP", Icons.Default.FolderZip, onZip)
        if (item.state in listOf("Failed", "Cancelled", "Paused", "Interrupted")) PrimaryButton(if (item.state in listOf("Paused", "Interrupted")) "Resume download" else "Retry download", Icons.Default.Refresh, onResume)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Text("Copy URL", Modifier.padding(start = 8.dp)) }
            OutlinedButton(onClick = onFolder, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Text("Open folder", Modifier.padding(start = 8.dp)) }
        }
        if (item.terminal || item.state in listOf("Paused", "Interrupted")) TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteOutline, null); Text("Remove from history", Modifier.padding(start = 8.dp)) }
    }
}

@Composable private fun SettingsScreen(prefs: Preferences, engine: String, onFolder: () -> Unit, onStorage: () -> Unit, onCookies: () -> Unit, onLogs: () -> Unit, onPlayer: () -> Unit, onHistory: () -> Unit, onUpdate: () -> Unit) {
    var proxyDialog by remember { mutableStateOf(false) }
    var proxy by remember { mutableStateOf(prefs.proxy) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding).padding(bottom = LocalMusicBottomPadding.current + 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Preferences", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("settings-title"))
        SettingsGroup("DOWNLOADS") {
            ChoiceRow("Save location", prefs.folderName, Icons.Default.FolderOpen, onFolder)
            SelectSetting("Concurrent downloads", prefs.concurrency.toString(), listOf("1", "2", "3")) { DownloadStore.savePrefs(prefs.copy(concurrency = it.toInt())) }
            ToggleRow("Start automatically", "Begin as soon as you add to queue", prefs.autoStart) { DownloadStore.savePrefs(prefs.copy(autoStart = it)) }
        }
        SettingsGroup("FORMAT & QUALITY") {
            SelectSetting("Audio quality", prefs.audioQuality, listOf("320", "256", "192", "128", "best"), { qualityLabel(true, it) }) { DownloadStore.savePrefs(prefs.copy(audioQuality = it)) }
            SelectSetting("Video quality", prefs.videoQuality, listOf("best", "2160", "1440", "1080", "720", "480"), { qualityLabel(false, it) }) { DownloadStore.savePrefs(prefs.copy(videoQuality = it)) }
        }
        SettingsGroup("PLAYLISTS") {
            ToggleRow("Preserve ordering", "Add the item number to filenames", prefs.preserveOrder) { DownloadStore.savePrefs(prefs.copy(preserveOrder = it)) }
            ToggleRow("Create ZIP automatically", "An archive when the playlist finishes", prefs.autoZip) { DownloadStore.savePrefs(prefs.copy(autoZip = it)) }
            ToggleRow("Keep individual media files", "Save the media alongside the ZIP", prefs.keepFolder) { DownloadStore.savePrefs(prefs.copy(keepFolder = it)) }
        }
        SettingsGroup("APPEARANCE & NOTIFICATIONS") {
            SelectSetting("Theme", prefs.theme, listOf("Dark", "Light", "System")) { DownloadStore.savePrefs(prefs.copy(theme = it)) }
            ToggleRow("Completion notifications", "Know when your media is ready", prefs.notifications) { DownloadStore.savePrefs(prefs.copy(notifications = it)) }
        }
        SettingsGroup("STORAGE") { ChoiceRow("Storage overview", "Media, ZIPs and temporary files", Icons.Default.Storage, onStorage) }
        SettingsGroup("MEDIA ENGINE") {
            Text(engine, color = if (engine.contains("ready")) Success else Crimson, fontSize = 12.sp)
            ChoiceRow("yt-dlp", DownloadStore.engineVersion(), Icons.Default.SystemUpdate, onUpdate)
            DetailRow("FFmpeg / Python / QuickJS", "Bundled · Android engine 0.18.1")
            SelectSetting("Network retries", prefs.retries.toString(), (0..5).map { it.toString() }) { DownloadStore.savePrefs(prefs.copy(retries = it.toInt())) }
            ChoiceRow("Cookies file", if (prefs.cookiesPath.isBlank()) "Optional · Netscape format" else "Imported into private storage", Icons.Default.Cookie, onCookies)
            if (prefs.cookiesPath.isNotBlank()) TextButton(onClick = { DownloadStore.savePrefs(prefs.copy(cookiesPath = "")) }) { Text("Remove cookies") }
            ChoiceRow("Proxy", prefs.proxy.ifBlank { "No proxy" }, Icons.Default.Language) { proxyDialog = true }
            ChoiceRow("Music player", "Playback, library and audio effects", Icons.Default.MusicNote, onPlayer)
            ChoiceRow("Download history", "Completed and previous downloads", Icons.Default.History, onHistory)
            ChoiceRow("Download logs", "Review errors and engine output", Icons.Default.Notes, onLogs)
        }
        Text("YTHub for Android · 1.0.2\nDownload. Convert. Manage.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
    }
    if (proxyDialog) GlassConfirmDialog(onDismissRequest = { proxyDialog = false }, title = { Text("Proxy") }, text = {
        OutlinedTextField(proxy, { proxy = it }, label = { Text("HTTPS or SOCKS proxy URL") }, placeholder = { Text("http://host:port") }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { DownloadStore.savePrefs(prefs.copy(proxy = proxy.trim())); proxyDialog = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { proxyDialog = false }) { Text("Cancel") } })
}

@Composable private fun StorageScreen(jobs: List<DownloadItem>, prefs: Preferences, onFolder: () -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    val music by MusicRepository.state.collectAsStateWithLifecycle()
    var storage by remember { mutableStateOf(MusicStorageUsage()) }
    LaunchedEffect(jobs, music) { storage = withContext(Dispatchers.IO) { MusicFileAccess.storage(context) } }
    val stats = StatFs(context.filesDir.absolutePath)
    val total = stats.totalBytes
    val free = stats.availableBytes
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding).padding(bottom = LocalMusicBottomPadding.current + 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Room for your favorites.", style = MaterialTheme.typography.headlineSmall)
        CardBlock {
            Label("DEVICE STORAGE")
            Text(bytesLabel(free), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("free of ${bytesLabel(total)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { (1f - free.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape), color = Crimson, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
        CardBlock {
            Label("TRACKED APP STORAGE")
            DetailRow("Audio", bytesLabel(storage.audio))
            DetailRow("Video", bytesLabel(storage.video))
            DetailRow("ZIP archives", bytesLabel(storage.archives))
            DetailRow("Imported audio", bytesLabel(storage.imported))
            DetailRow("Artwork cache", bytesLabel(storage.artwork))
            DetailRow("Temporary files", bytesLabel(storage.temporary))
            DetailRow("Total tracked app storage", bytesLabel(storage.total))
            Text("Counts accessible download outputs and app-owned imports. External imported files and outputs removed from history are excluded.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CardBlock {
            Label("DOWNLOAD LOCATION")
            Text(prefs.folderName, fontWeight = FontWeight.SemiBold)
            PrimaryButton("Choose folder", Icons.Default.FolderOpen, onFolder)
            if (prefs.folderUri.isNotBlank()) TextButton(onClick = { DownloadStore.savePrefs(prefs.copy(folderUri = "", folderName = "Downloads / YTHub")) }) { Text("Use default Downloads folder") }
        }
        OutlinedButton(onClick = MusicRepository::clearArtwork, modifier = Modifier.fillMaxWidth()) { Text("Clear artwork cache") }
        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Default.CleaningServices, null); Text("Clear temporary files", Modifier.padding(start = 10.dp)) }
        Text("Paused and interrupted downloads keep their partial files so they can resume.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun LogScreen(jobs: List<DownloadItem>) {
    LazyColumn(contentPadding = PaddingValues(start = PagePadding, end = PagePadding, top = PagePadding, bottom = LocalMusicBottomPadding.current + 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (jobs.isEmpty()) item { Text("No download logs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(jobs, key = { it.id }) { item -> CardBlock {
            Text(item.media.title, fontWeight = FontWeight.Bold)
            Text("${item.state} · ${item.id}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.error.ifBlank { item.line }, fontSize = 12.sp)
        } }
    }
}

@Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable private fun Label(text: String) { Text(text, fontSize = 11.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable internal fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Crimson)) {
        Icon(icon, null, Modifier.size(20.dp)); Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
    }
}
@Composable private fun BadgeText(text: String) { Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) { Text(text, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) } }
@Composable internal fun FormatSelector(audio: Boolean, onAudio: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(true to "Audio · MP3", false to "Video · MP4").forEach { (isAudio, label) ->
            val selected = isAudio == audio
            Surface(onClick = { onAudio(isAudio) }, color = if (selected) Crimson.copy(alpha = .13f) else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(50), border = BorderStroke(.65.dp, if (selected) Crimson.copy(alpha = .5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .10f)), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (isAudio) Icons.Default.MusicNote else Icons.Default.Videocam, null, Modifier.size(22.dp), tint = if (selected) Crimson else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 2)
                }
            }
        }
    }
}
@Composable private fun ChoiceRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Crimson)
        Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp); Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title }, colors = SwitchDefaults.colors(checkedTrackColor = Crimson, checkedThumbColor = Color.White))
    }
}
@Composable private fun FilterTabs(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { option ->
        Surface(onClick = { onSelect(option) }, modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 48.dp), shape = RoundedCornerShape(12.dp), color = if (selected == option) Crimson else MaterialTheme.colorScheme.surface) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) { Text(option, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected == option) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    } }
}
@Composable private fun Thumbnail(url: String, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url.isBlank()) Image(painterResource(R.drawable.ythub_logo), "YTHub", Modifier.size(40.dp))
        else AsyncImage(model = url, contentDescription = "Media thumbnail", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, error = painterResource(R.drawable.ythub_logo))
    }
}
@Composable private fun EmptyState(title: String, text: String, onHome: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalMusicBottomPadding.current).padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.ythub_logo), "YTHub", Modifier.size(44.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        PrimaryButton("Find something to save", Icons.Default.Link, onHome)
    }
}
@Composable private fun DetailRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
}
@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val label=when(title) {
        "DOWNLOADS" -> "Downloads"
        "FORMAT & QUALITY" -> "Format & quality"
        "PLAYLISTS" -> "Playlists"
        "APPEARANCE & NOTIFICATIONS" -> "Appearance & notifications"
        "STORAGE" -> "Storage"
        "MEDIA ENGINE" -> "Media engine & tools"
        else -> title
    }
    val summary=when(title) {
        "DOWNLOADS" -> "Location, queue and automatic downloads"
        "FORMAT & QUALITY" -> "Audio and video preferences"
        "PLAYLISTS" -> "Ordering, archives and saved files"
        "APPEARANCE & NOTIFICATIONS" -> "Theme and completion alerts"
        "STORAGE" -> "Manage files and device space"
        else -> "Updates, network and diagnostics"
    }
    MusicSettingsSection(label, summary, initiallyExpanded = title == "DOWNLOADS", content = content)
}
@Composable private fun SelectSetting(title: String, value: String, options: List<String>, display: (String) -> String = { it }, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ChoiceRow(title, display(value), Icons.Default.Tune) { open = true }
    if (open) GlassSheet(onDismissRequest = { open = false }) {
        Column(Modifier.padding(PagePadding).navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
            options.forEach { option -> Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onSelect(option); open = false }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(option == value, { onSelect(option); open = false }); Text(display(option), modifier = Modifier.padding(start = 8.dp))
            } }
        }
    }
}
private fun copyText(context: Context, url: String) { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("YTHub source URL", url)) }
private fun openMedia(context: Context, value: String, mime: String) {
    require(value.isNotBlank()) { "This file is not available yet." }
    val uri = Uri.parse(value)
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.close() ?: error("This saved file has been removed or is unavailable.")
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
}
private fun openFolder(context: Context, folder: String) {
    val uri = if (folder.isBlank()) Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FYTHub") else DocumentFile.fromTreeUri(context, Uri.parse(folder))?.uri ?: Uri.parse(folder)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
    else context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra("android.provider.extra.INITIAL_URI", uri))
}
