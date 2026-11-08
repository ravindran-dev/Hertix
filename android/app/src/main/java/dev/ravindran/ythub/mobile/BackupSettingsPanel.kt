package dev.ravindran.ythub.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun BackupSettingsPanel(onOnline:()->Unit) {
    val context=LocalContext.current
    val account by CloudAccount.state.collectAsStateWithLifecycle()
    val progress by CloudBackupPolicy.progress.collectAsStateWithLifecycle()
    var consent by remember(account.userId) {mutableStateOf(CloudBackupPolicy.settings(context))}
    var editing by remember {mutableStateOf(false)}
    var data by remember {mutableStateOf(false)}
    var music by remember {mutableStateOf(false)}
    var automatic by remember {mutableStateOf(false)}
    var mobile by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if(!allowed)message="Notifications are off. Backup progress is still shown here."
    }
    fun edit() {data=consent.data;music=consent.music;automatic=consent.automatic;mobile=consent.mobile;editing=true}
    Text("Online backup",style=MaterialTheme.typography.titleLarge)
    Text("Offline stays on this phone. Online stores the data and music you allow in your account.",style=MaterialTheme.typography.bodySmall)
    if(!consent.data && !consent.music)Text("Nothing is uploaded until you allow it.")
    else Text("${if(consent.automatic) "Automatic" else "Manual"} · ${if(consent.mobile) "Wi-Fi or mobile data" else "Wi-Fi only"} · ${listOfNotNull(if(consent.data) "library data" else null,if(consent.music) "music files" else null).joinToString(" + ")}")
    AccountActionButton("Choose backup contents", Icons.Outlined.Tune, ::edit, subtitle="Select music, library data and network rules", enabled=!progress.busy)
    if(progress.busy) AccountActionButton("Pause cloud backup", Icons.Outlined.Pause, {CloudBackupPolicy.stop(context)}, subtitle="${progress.percent}% complete", enabled=true)
    else AccountActionButton("Back up now", Icons.Outlined.Cloud, {try {CloudBackupPolicy.schedule(context)} catch(e:Exception){message=e.message.orEmpty()}}, subtitle="Upload only the items you allowed", enabled=consent.data||consent.music)
    if(progress.message.isNotBlank()) Text(progress.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(progress.busy) LinearProgressIndicator(progress={progress.percent/100f},modifier=Modifier.fillMaxWidth())
    AccountActionButton("Open Online library", Icons.Outlined.Cloud, onOnline, subtitle="Browse files saved to your account")
    if(message.isNotBlank())Text(message)
    if(editing)GlassConfirmDialog(onDismissRequest={editing=false},title={Text("Allow cloud backup?")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row {Checkbox(data,{data=it},Modifier.testTag("backup-consent-data"));Column {Text("Library data");Text("Playlists, favorites and song details",style=MaterialTheme.typography.bodySmall)}}
        Row {Checkbox(music,{music=it},Modifier.testTag("backup-consent-music"));Column {Text("Music files");Text("Upload local audio; uses storage and data. Cloud size and format limits apply.",style=MaterialTheme.typography.bodySmall)}}
        Row {Checkbox(automatic,{automatic=it},Modifier.testTag("backup-consent-auto"));Text("Automatically back up changes")}
        Text("Allowed network",style=MaterialTheme.typography.titleSmall)
        Row {RadioButton(!mobile,{mobile=false},Modifier.testTag("backup-network-wifi"));Text("Wi-Fi only")}
        Row {RadioButton(mobile,{mobile=true},Modifier.testTag("backup-network-mobile"));Text("Wi-Fi or mobile data")}
        Text("Original files remain on your phone. Android schedules automatic backups when the allowed network is available.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={
        try {
            val next=BackupConsent(data,music,automatic,mobile)
            CloudBackupPolicy.approve(context,next);consent=next;editing=false
            if((data||music)&&Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch(e:Exception) {message=e.message.orEmpty()}
    }) {Text(if(data||music) "Allow backup" else "Turn backup off")}},dismissButton={TextButton(onClick={editing=false}) {Text("Cancel")}})
}

@Composable
internal fun BackupConflictReview() {
    val sync by CloudSync.state.collectAsStateWithLifecycle()
    val conflicts=sync.queue.filter {it.status=="conflict"}
    var expanded by remember {mutableStateOf(false)}
    if(conflicts.isEmpty())return
    Text("${conflicts.size} library changes need review",style=MaterialTheme.typography.titleMedium)
    Text("Your offline library is safe. Different versions remain saved until you choose.",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={expanded=!expanded}) {Text(if(expanded) "Hide review" else "Review changes")}
    if(expanded)conflicts.take(10).forEach {op ->
        HorizontalDivider()
        Text(SyncConflictRules.title(op,sync.rows),style=MaterialTheme.typography.titleSmall)
        SyncConflictRules.playlistName(op,sync.rows)?.let {Text("Playlist: $it",style=MaterialTheme.typography.bodySmall)}
        op.patch.entrySet().filter {it.key !in listOf("library_item_id","playlist_id")}.forEach {(key,value) ->
            Text("${key.replace('_',' ')}: phone $value · cloud ${op.remote?.get(key) ?: "missing"}",style=MaterialTheme.typography.bodySmall)
        }
        Row {TextButton(onClick={CloudSync.resolve(op.operation_id,true)}) {Text("Keep phone version")};TextButton(onClick={CloudSync.resolve(op.operation_id,false)}) {Text("Use cloud version")}}
    }
    if(expanded&&conflicts.size>10)Text("Resolve these to see the remaining changes.",style=MaterialTheme.typography.bodySmall)
}
