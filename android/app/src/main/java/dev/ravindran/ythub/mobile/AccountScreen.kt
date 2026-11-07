package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(onCloudLibrary: () -> Unit = {}, onSettings: () -> Unit = {}, onPlayerSettings: () -> Unit = {}, onDownloads: () -> Unit = {}, onAbout: () -> Unit = {}) {
    val account by CloudAccount.state.collectAsStateWithLifecycle()
    val sync by CloudSync.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var mode by remember { mutableStateOf("Sign in") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var editingProfile by remember { mutableStateOf(false) }
    var profileName by remember(account.displayName) { mutableStateOf(account.displayName) }
    fun action(block: suspend ()->Unit) { scope.launch { busy=true;message="";try { block() } catch(e: Exception) { message=e.message ?: "Request failed" } finally {busy=false;password=""} } }
    LaunchedEffect(account.status) { if(account.status=="Account recovery") mode="New password" }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
    Surface(modifier=Modifier.widthIn(max=520.dp).fillMaxSize(),color=androidx.compose.ui.graphics.Color.Transparent) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if (account.userId != null) AccountProfileCard(account, onEdit = { profileName = account.displayName.ifBlank { account.email.substringBefore('@') }; editingProfile = true })
        else Column(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Cloud,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary)
            Text("Your music, anywhere",style=MaterialTheme.typography.titleLarge)
            Text("Offline on your phone. Online in your account.",style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AccountModule("Offline backup", "Local library safety copy", Icons.Outlined.Save) { LocalLibraryBackup() }
        if(!CloudAccount.configured) {
            Text("Cloud unavailable",style=MaterialTheme.typography.titleLarge)
            Text("Cloud accounts are unavailable in this build. Your local library, backups and downloads are available.")
        } else if(account.userId!=null && mode!="New password") {
            AccountModule("Account", "Sign-in and security", Icons.Outlined.Person) {
                Text(account.email,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("${if(account.verified) "Email verified" else "Verification pending"} · ${account.status}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                MusicActionRow("Edit profile", Icons.Outlined.Edit, { profileName=account.displayName.ifBlank { account.email.substringBefore('@') }; editingProfile=true }, forward = true)
                MusicActionRow("Change password", Icons.Outlined.Lock, { mode="New password" }, forward = true)
                MusicActionRow("Sign out", Icons.Outlined.Logout, { action { CloudAccount.signOut(); message="Signed out" } }, forward = true)
            }
            AccountModule("Cloud & Synchronization", "Library, backup and storage", Icons.Outlined.Cloud) { BackupSettingsPanel(onCloudLibrary); BackupConflictReview() }
            AccountModule("Music Player", "Playback and audio preferences", Icons.Outlined.PlayArrow) {
                MusicActionRow("Playback preferences", Icons.Outlined.Settings, onPlayerSettings, forward = true)
                MusicActionRow("Audio quality and equalizer", Icons.Outlined.Equalizer, onPlayerSettings, forward = true)
            }
            AccountModule("Downloads", "Queue and storage controls", Icons.Outlined.Download) {
                MusicActionRow("Download queue", Icons.Outlined.Download, onDownloads, forward = true)
                MusicActionRow("Download preferences", Icons.Outlined.Settings, onSettings, forward = true)
                MusicActionRow("Storage management", Icons.Outlined.Storage, onSettings, forward = true)
            }
            AccountModule("Appearance", "Theme and interface", Icons.Outlined.Palette) { MusicActionRow("Appearance settings", Icons.Outlined.DarkMode, onSettings, forward = true) }
            AccountModule("Privacy & Security", "Account data and sessions", Icons.Outlined.Security) {
                AccountExportButton()
                MusicActionRow("Sign out other sessions", Icons.Outlined.Devices, { action { CloudAccount.signOut(true); message="Other refresh sessions revoked. Existing access tokens expire under the server policy." } }, forward = true)
                MusicActionRow("Delete account", Icons.Outlined.Delete, { deleting=true }, destructive = true)
            }
            AccountModule("About", "Version and diagnostics", Icons.Outlined.Info) {
                MusicActionRow("About YTHub", Icons.Outlined.Info, onAbout, subtitle = "Version ${BuildConfig.VERSION_NAME}", forward = true)
            }
            if(deleting) AlertDialog(onDismissRequest={if(!busy)deleting=false},title={Text("Permanently delete account?")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Your cloud files, playlists and metadata will be deleted. Local downloaded files remain. This cannot be undone.")
                OutlinedTextField(confirmation,{confirmation=it},label={Text("Type DELETE MY ACCOUNT")})
                OutlinedTextField(password,{password=it},label={Text("Current password")},visualTransformation=PasswordVisualTransformation())
            }},confirmButton={TextButton(enabled=!busy&&confirmation=="DELETE MY ACCOUNT"&&password.isNotEmpty(),onClick={action {
                CloudAccount.api("/functions/v1/account-files","POST",json("action" to "delete-account","confirmation" to confirmation,"password" to password))
                CloudAccount.forgetDeletedAccount();deleting=false;message="Account deleted. Local files remain."
            }}){Text("Delete permanently")}},dismissButton={TextButton(enabled=!busy,onClick={deleting=false;password=""}){Text("Cancel")}})
        } else {
            Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(mode,style=MaterialTheme.typography.titleLarge)
            if(mode!="New password") OutlinedTextField(email,{email=it},label={Text("Email address")},leadingIcon={Icon(Icons.Outlined.Email,null)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),singleLine=true,modifier=Modifier.fillMaxWidth())
            if(mode=="Create account") OutlinedTextField(name,{name=it.take(100)},label={Text("Display name")},leadingIcon={Icon(Icons.Outlined.Person,null)},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(mode in listOf("Sign in","Create account","New password")) OutlinedTextField(password,{password=it},label={Text("Password")},leadingIcon={Icon(Icons.Outlined.Lock,null)},supportingText={if(mode!="Sign in") Text("At least 12 characters")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            if(mode in listOf("Verify email","Recovery")) OutlinedTextField(code,{code=it.trim()},label={Text("Email code")},leadingIcon={Icon(Icons.Outlined.Pin,null)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
            Button(onClick={action {
                when(mode) {
                    "Sign in" -> CloudAccount.signIn(email,password)
                    "Create account" -> {CloudAccount.signUp(email,password,name);mode="Verify email";message="If registration is available, check your email for a code."}
                    "Verify email" -> CloudAccount.verify(email,code,false)
                    "Recovery" -> if(code.isBlank()) {CloudAccount.sendCode(email,true);message="If eligible, this address will receive a recovery code."} else CloudAccount.verify(email,code,true)
                    "New password" -> {CloudAccount.password(password);mode="Sign in";message="Password updated"}
                }
            }},enabled=!busy && account.status!="Initializing",modifier=Modifier.fillMaxWidth()) { Text(if(busy) "Please wait…" else if(mode=="Recovery" && code.isBlank()) "Send recovery code" else mode) }
            Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {TextButton(onClick={mode=if(mode=="Sign in") "Create account" else "Sign in";code=""},enabled=!busy) {Text(if(mode=="Sign in") "Create account" else "Back to sign in")};TextButton(onClick={mode="Recovery";code=""},enabled=!busy) {Text("Forgot password?")}}
            if(mode=="Verify email") TextButton(onClick={action {CloudAccount.sendCode(email,false);message="If eligible, a new code has been sent."}},enabled=!busy) {Text("Resend code")}
            Text("Local playback and downloads work without an account.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
            } }
        }
        if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.primary)
        if(account.message.isNotBlank()) Text(account.message)
        if(editingProfile) AlertDialog(onDismissRequest={if(!busy)editingProfile=false},title={Text("Edit profile")},text={OutlinedTextField(profileName,{profileName=it.take(100)},label={Text("Display name")},singleLine=true)},confirmButton={TextButton(enabled=!busy&&profileName.isNotBlank(),onClick={action {CloudAccount.updateDisplayName(profileName);editingProfile=false;message="Profile updated"}}){Text(if(busy)"Saving…" else "Save")}},dismissButton={TextButton(enabled=!busy,onClick={editingProfile=false}){Text("Cancel")}})
        Spacer(Modifier.height(LocalMusicBottomPadding.current + 20.dp))
    }
    }
    }
}

@Composable
private fun AccountGroup(content: @Composable ColumnScope.() -> Unit) {
    LiquidGlassPanel(modifier=Modifier.fillMaxWidth(),backdrop=LocalAtmosphereLayer.current,radius=24.dp,animateOnTouch=false) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
