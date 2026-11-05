package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CloudLibraryScreen() {
    var error by remember {mutableStateOf("")}
    LaunchedEffect(Unit) {try {CloudSync.refreshOnline()} catch(e:Exception) {error=e.message ?: "Could not refresh Online. Offline files are safe."}}
    Column(Modifier.fillMaxSize()) {
    if(error.isNotBlank())Text(error,Modifier.padding(20.dp))
    Box(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)) {
        DeviceLibrarySection()
    }
    }
}
