package dev.ravindran.ythub.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CloudFilesSection() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val transfer by CloudMedia.state.collectAsStateWithLifecycle()
    val music by MusicRepository.state.collectAsStateWithLifecycle()
    var files by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var usage by remember { mutableStateOf<JsonObject?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf<JsonObject?>(null) }
    var deleting by remember { mutableStateOf<JsonObject?>(null) }
    var message by remember { mutableStateOf("") }
    suspend fun refresh() {try {files=CloudMedia.files();usage=CloudMedia.usage()} catch(_:Exception){message="Cloud files could not be loaded. Retry when connected."}}
    LaunchedEffect(transfer.busy) {if(!transfer.busy)refresh()}
    HorizontalDivider()
    Text("Private cloud files",style=MaterialTheme.typography.titleLarge)
    usage?.let {Text("${it.get("used_bytes").asLong/1048576} MB reserved or stored / ${it.get("quota_bytes").asLong/1048576} MB")}
    Text("Upload only music you own or are authorized to store. Local files remain on this phone when you delete a cloud copy.",style=MaterialTheme.typography.bodySmall)
    Box {
        OutlinedButton(onClick={menu=true},enabled=!transfer.busy) {Text(music.tracks.find {it.id==selected}?.title ?: "Choose a local song")}
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {music.tracks.filter {it.available && !it.uri.startsWith("cloud://")}.forEach {track -> DropdownMenuItem(text={Text(track.title,maxLines=1)},onClick={selected=track.id;menu=false})}}
    }
    Row {Checkbox(allowed,{allowed=it});Text("I’m authorized to store this audio.",Modifier.padding(top=12.dp))}
    Button(onClick={music.tracks.find {it.id==selected}?.let {CloudMedia.upload(context.applicationContext,it,retry)}},enabled=allowed&&selected!=null&&!transfer.busy){Text(if(retry==null) "Upload selected song" else "Retry selected upload")}
    if(transfer.busy){LinearProgressIndicator(progress={transfer.progress/100f},modifier=Modifier.fillMaxWidth());TextButton(onClick={CloudMedia.cancel()}){Text("Cancel transfer")}}
    if(transfer.message.isNotBlank())Text(transfer.message)
    files.forEach {record ->
        val status=record.get("status").asString
        Text(record.get("display_name").asString,style=MaterialTheme.typography.titleSmall)
        Text("${record.get("bytes").asLong/1024} KB · $status",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(status=="ready")TextButton(onClick={CloudMedia.download(context.applicationContext,record)},enabled=!transfer.busy){Text("Download to phone")}
            else TextButton(onClick={retry=record;message="Select the original local song, then retry the upload."},enabled=!transfer.busy){Text("Retry upload")}
            TextButton(onClick={deleting=record},enabled=!transfer.busy){Text("Remove cloud copy")}
        }
    }
    TextButton(onClick={scope.launch {refresh()}},enabled=!transfer.busy){Text("Refresh files")}
    if(message.isNotBlank())Text(message)
    deleting?.let {record -> AlertDialog(onDismissRequest={deleting=null},title={Text("Delete cloud copy?")},text={Text("This removes the private cloud file. Files already downloaded on this or other devices remain available.")},confirmButton={TextButton(onClick={deleting=null;scope.launch {try {CloudMedia.action("delete",record.get("id").asString);refresh()}catch(_:Exception){message="Deletion did not complete. Retry when connected."}}}){Text("Delete cloud copy")}},dismissButton={TextButton(onClick={deleting=null}){Text("Cancel")}})}
}

@Composable
fun AccountExportButton() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var message by remember {mutableStateOf("")}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null)scope.launch {
        try {withContext(Dispatchers.IO) {
            val owner=CloudAccount.state.value.userId ?: error("Sign in first")
            val result=JsonObject()
            for(table in listOf("profiles","user_devices","library_items","playlists","playlist_items","favorites","playback_history","playback_preferences","cloud_objects")) {
                val rows=JsonArray();var offset=0
                val key=if(table=="favorites") "library_item_id" else if(table=="playback_preferences") "user_id" else "id"
                while(true) {val page=CloudAccount.api("/rest/v1/$table?select=*&order=$key&limit=500&offset=$offset",expectedOwner=owner).asJsonArray;rows.addAll(page);if(page.size()<500)break;offset+=500};result.add(table,rows)
            }
            context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use {it.write(result.toString())} ?: error("File unavailable")
        };message="Account metadata exported. Audio files are not included."} catch(_:Exception){message="Export failed. Retry when connected."}
    } }
    OutlinedButton(onClick={launcher.launch("ythub-account-export.json")}){Text("Export account data")}
    if(message.isNotBlank())Text(message)
}
