package dev.ravindran.ythub.mobile

import android.app.*
import android.app.job.*
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.time.Instant

/** System-managed, network-constrained backup. Receipts are server-confirmed files, never a UI flag. */
class CloudBackupJob:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var task:Job?=null
    private var activeId=-1
    override fun onStartJob(params:JobParameters):Boolean {
        if(task?.isActive==true)return false
        activeId=params.jobId
        task=scope.launch {
            var retry=false
            try { withTimeout(4*60*1000L) { retry=backup(params.extras.getString("owner") ?: error("Account unavailable")) } }
            catch(e:TimeoutCancellationException) { report("Backup paused; Android will retry",CloudBackupPolicy.progress.value.percent,false);retry=true }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { report(e.message ?: "Backup could not finish; retry available",CloudBackupPolicy.progress.value.percent,false);retry=true }
            finally {CloudBackupPolicy.runningOwner=null}
            jobFinished(params,retry)
        }
        return true
    }
    private suspend fun backup(owner:String):Boolean {
        withTimeout(30_000) {CloudAccount.state.first {it.status!="Initializing"}; MusicRepository.ready.first {it}}
        check(CloudAccount.state.value.userId==owner) {"Account changed; backup stopped"}
        val consent=CloudBackupPolicy.settings(this)
        if(!consent.data && !consent.music)return false
        check(CloudBackupPolicy.networkAllowed(this)) {"Waiting for allowed network"}
        CloudSync.awaitAccount(owner)
        CloudBackupPolicy.runningOwner=owner
        var failures=0;var completed=0;var retry=false
        val problems=mutableListOf<String>()
        val tracks=if(consent.music)MusicRepository.state.value.tracks.filter {it.available && !it.uri.startsWith("cloud://")} else emptyList()
        val total=(tracks.size+if(consent.data)1 else 0).coerceAtLeast(1)
        report("Preparing backup",0,true)
        if(consent.data) {
            report("Backing up playlists and library data",0,true)
            CloudSync.backupNow()
            when(CloudSync.state.value.status) {
                "Synced" -> completed++
                "Needs review" -> { }
                else -> {failures++;retry=true;problems+="Library data is waiting: ${CloudSync.state.value.status}"}
            }
        }
        var records=if(consent.music)CloudMedia.files() else emptyList()
        val maxBytes=if(consent.music)CloudMedia.usage().get("max_file_bytes").asLong else 0L
        for((index,track) in tracks.withIndex()) {
            currentCoroutineContext().ensureActive()
            check(CloudAccount.state.value.userId==owner) {"Account changed; backup stopped"}
            val now=CloudBackupPolicy.settings(this)
            if(!now.music)break
            check(CloudBackupPolicy.networkAllowed(this)) {"Waiting for allowed network"}
            val base=index+if(consent.data)1 else 0
            try {
                report("Checking ${index+1}/${tracks.size}: ${track.title}",base*100/total,true,completed,failures)
                val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
                contentResolver.openInputStream(Uri.parse(track.uri))?.use { input ->
                    val buffer=ByteArray(65536)
                    while(true) {currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=maxBytes){"File exceeds the cloud size limit"};digest.update(buffer,0,n)}
                } ?: error("Local file is unavailable")
                val hash=digest.digest().joinToString("") {"%02x".format(it)}
                if(records.none {it.get("status").asString=="ready" && it.get("sha256").asString==hash && it.get("bytes").asLong==bytes}) {
                    val pending=records.find {it.get("status").asString in listOf("pending","verifying") && it.get("sha256").asString==hash && it.get("bytes").asLong==bytes}
                    val expired=pending?.get("expires_at")?.asString?.let {runCatching {Instant.parse(it).isBefore(Instant.now())}.getOrDefault(false)}==true
                    if(expired)CloudMedia.action("delete",pending!!.get("id").asString,owner)
                    CloudMedia.uploadFile(applicationContext,track,if(expired)null else pending) { percent ->
                        report("Backing up ${index+1}/${tracks.size}: ${track.title}",(base*100+percent)/total,true,completed,failures)
                    }
                    records=CloudMedia.files()
                    check(records.any {it.get("status").asString=="ready" && it.get("sha256").asString==hash}) {"Server has not confirmed this file"}
                }
                completed++
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {
                failures++;problems+="${track.title}: ${e.message ?: "Upload failed"}"
                if(e is CloudHttpException) {if(e.status==408 || e.status==429 || e.status>=500)retry=true} else if(e is java.io.IOException)retry=true
            }
        }
        val conflicts=if(consent.data)CloudSync.state.value.queue.count {it.status=="conflict"} else 0
        val text=when {
            failures>0 -> "$completed saved; $failures need attention. ${problems.take(3).joinToString(" · ")}"
            conflicts>0 -> if(consent.music) "Music files saved; $conflicts library changes need review" else "$conflicts library changes need review"
            else -> "Backup complete · $completed items confirmed"
        }
        report(text,100,false,completed,failures)
        CloudBackupPolicy.result(this,text)
        return retry
    }
    private fun report(text:String,percent:Int,busy:Boolean,completed:Int=0,failed:Int=0) {
        CloudBackupPolicy.progress.value=BackupProgress(busy,percent.coerceIn(0,100),text,completed,failed)
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("backups","Cloud backup",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,704,Intent(this,MainActivity::class.java).putExtra("open_account",true),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(this,"backups").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("YTHub backup").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(busy).setAutoCancel(!busy).apply {if(busy)setProgress(100,percent,false)}.build()
        runCatching {manager.notify(704,notification)}
    }
    override fun onStopJob(params:JobParameters):Boolean {
        if(params.jobId==activeId) {task?.cancel();CloudBackupPolicy.runningOwner=null;report("Backup paused; will resume when allowed",CloudBackupPolicy.progress.value.percent,false)}
        return true
    }
    override fun onDestroy() {scope.cancel();super.onDestroy()}
}
