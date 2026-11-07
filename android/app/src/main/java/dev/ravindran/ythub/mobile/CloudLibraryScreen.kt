package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CloudLibraryScreen() {
    var error by remember {mutableStateOf("")}
    LaunchedEffect(Unit) { MusicRepository.restoreMissingArtwork() }
    LaunchedEffect(Unit) {try {CloudSync.refreshOnline()} catch(e:Exception) {error=e.message ?: "Could not refresh Online. Offline files are safe."}}
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        if (error.isNotBlank()) Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .42f)) {
            Text(error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Box(Modifier.weight(1f).padding(bottom = 4.dp)) { DeviceLibrarySection() }
    }
}
