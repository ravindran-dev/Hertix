package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@Composable
internal fun AppUpdateCheckerScreen() {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AppUpdateStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun check() {
        checking = true
        error = null
        runCatching { AppUpdater.checkForUpdate() }.onSuccess { result = it }.onFailure { error = it.message ?: "Could not check for updates." }
        checking = false
    }
    LaunchedEffect(Unit) { check() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassControlSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), tintAlpha = .2f) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Software Update", style = MaterialTheme.typography.titleLarge)
                        Text("Hertix ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when {
                    checking -> Text("Checking GitHub Releases…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    result == AppUpdateStatus.NoStableRelease -> Text("No stable release has been published yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result == AppUpdateStatus.UpToDate -> Text("You're up to date.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result is AppUpdateStatus.Available -> Text("Hertix ${(result as AppUpdateStatus.Available).release.versionName} is ready to download.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                (result as? AppUpdateStatus.Available)?.let { UpdateReleaseActions(it.release) }
                OutlinedButton(enabled = !checking, onClick = { scope.launch { check() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (checking) "Checking…" else "Check for updates")
                }
            }
        }
    }
}

@Composable
internal fun AppUpdateAvailableDialog(release: YTHubRelease, onRemindLater: () -> Unit) {
    val height = LocalConfiguration.current.screenHeightDp.dp
    val width = LocalConfiguration.current.screenWidthDp.dp
    val notes = remember(release.notes, release.versionName) { readableReleaseNotes(release.notes, release.versionName) }
    val hasNotes = notes != "Release notes are not available."
    Dialog(onDismissRequest = onRemindLater, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)), contentAlignment = Alignment.Center) {
        val panelMaxHeight = (height * .82f).coerceAtMost(640.dp)
        LiquidGlassPanel(
            Modifier.width(minOf(width - 40.dp, 310.dp)).heightIn(max = panelMaxHeight).clip(RoundedCornerShape(22.dp)),
            backdrop = LocalGlassScene.current?.layer ?: LocalAtmosphereLayer.current,
            contentOrigin = LocalGlassScene.current?.origin ?: Offset.Zero,
            radius = 24.dp,
            tintAlpha = .18f,
            animateOnTouch = false
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).padding(horizontal = 18.dp, vertical = 15.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Software Update", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Hertix ${release.versionName} is available to download.", Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 11.dp), color = Color.White.copy(alpha = .18f))
                Text("What’s new", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasNotes) Column(Modifier.fillMaxWidth().heightIn(max = minOf(height * .28f, 220.dp)).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 10.dp)) {
                    Text(notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                } else Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.TopStart) {
                    Text(notes, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = Color.White.copy(alpha = .18f))
                UpdateReleaseActions(release, showNotes = false)
                HorizontalDivider(color = Color.White.copy(alpha = .14f))
                TextButton(onClick = onRemindLater, modifier = Modifier.fillMaxWidth().height(44.dp), contentPadding = PaddingValues(0.dp)) {
                    Text("Remind Me Later", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        }
    }
}

private fun readableReleaseNotes(raw: String, versionName: String): String {
    val lines = raw.lineSequence()
        .filterNot { it.contains("full changelog", ignoreCase = true) || it.trim().startsWith("https://github.com/", ignoreCase = true) }
        .map { line ->
            line.trim()
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                .replace(Regex("(?m)^[-*]\\s+"), "• ")
        }
        .filter(String::isNotBlank)
        .filterNot { it.trim().removePrefix("•").trim().equals("Hertix $versionName", ignoreCase = true) }
        .toList()
    return lines.joinToString("\n").ifBlank { "Release notes are not available." }
}

@Composable
private fun UpdateReleaseActions(release: YTHubRelease, showNotes: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var apk by remember(release.versionName) { mutableStateOf<java.io.File?>(null) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showNotes && release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (downloading) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        val action: () -> Unit = {
            scope.launch {
                failed = false
                if (apk == null) {
                    downloading = true
                    message = "Downloading Hertix ${release.versionName}…"
                    runCatching { AppUpdater.download(context, release) { progress = it }.also { AppUpdater.verifyUpdateSigner(context, it) } }
                        .onSuccess { apk = it; message = "Download complete. Android will ask you to confirm installation."; progress = 0f }
                        .onFailure { message = it.message ?: "Download failed."; failed = true; progress = 0f }
                    downloading = false
                } else {
                    runCatching { AppUpdater.launchInstaller(context, apk!!) }
                        .onSuccess { opened -> message = if (opened) "Confirm the update in Android's installer." else "Allow Hertix to install apps, return here, and tap Install update." }
                        .onFailure { message = it.message ?: "Could not open Android's installer."; failed = true }
                }
            }
        }
        if (!showNotes) TextButton(enabled = !downloading, onClick = action, modifier = Modifier.fillMaxWidth().height(44.dp), contentPadding = PaddingValues(0.dp)) {
            Text(if (downloading) "Downloading…" else if (apk == null) "Download Now" else "Install Update", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        } else Button(enabled = !downloading, onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(if (downloading) "Downloading…" else if (apk == null) "Download Now" else "Install Update")
        }
    }
}
