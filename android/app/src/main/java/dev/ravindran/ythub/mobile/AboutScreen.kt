package dev.ravindran.ythub.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AboutScreen(onDiagnostics: () -> Unit = {}, onBack: () -> Unit = {}, onUpdates: () -> Unit = {}) {
    var showingLicenses by remember { mutableStateOf(false) }
    BackHandler(enabled = !showingLicenses, onBack = onBack)
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassControlSurface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), tintAlpha = .24f) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Hertix ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Text("Music library, playback, downloads and cloud sync.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MusicActionRow("App updates", Icons.Filled.SystemUpdate, onUpdates, subtitle = "Check for Hertix updates", forward = true)
                MusicActionRow("Open-source notices", Icons.Rounded.Description, { showingLicenses = true }, forward = true)
                MusicActionRow("Diagnostics", Icons.Rounded.BugReport, onDiagnostics, subtitle = "Review local download and playback events", forward = true)
            }
        }
    }
    if (showingLicenses) GlassConfirmDialog(onDismissRequest = { showingLicenses = false }, title = { Text("Open-source notices") },
        text = { Text("Hertix Android is released under the GPL-3.0 License. Third-party dependency notices are included with the Hertix source distribution.") },
        confirmButton = { TextButton(onClick = { showingLicenses = false }) { Text("Done") } })
}
