package dev.ravindran.ythub.mobile

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BackupConsent(val data: Boolean=false, val music: Boolean=false, val automatic: Boolean=false, val mobile: Boolean=false) {
    fun allowsNetwork(wifi:Boolean,validated:Boolean)=validated && (mobile || wifi)
}
data class BackupProgress(val busy: Boolean=false,val percent: Int=0,val message: String="Backup is off",val completed: Int=0,val failed: Int=0)
object CloudBackupPolicy {
    const val AUTOMATIC_JOB=701
    const val MANUAL_JOB=702
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val progress=MutableStateFlow(BackupProgress())
    val openAccount=MutableStateFlow(false)
    @Volatile var runningOwner: String?=null
    private fun prefs(ctx:Context,owner:String?=CloudAccount.state.value.userId)=ctx.getSharedPreferences("backup-consent-${owner ?: "guest"}",0)
    fun settings(ctx:Context):BackupConsent=prefs(ctx).let {BackupConsent(it.getBoolean("data",false),it.getBoolean("music",false),it.getBoolean("automatic",false),it.getBoolean("mobile",false))}
    fun permitsMetadata(ctx:Context):Boolean {val owner=CloudAccount.state.value.userId ?: return false;val s=settings(ctx);return s.data && runningOwner==owner}
    fun networkAllowed(ctx:Context):Boolean {
        val cm=ctx.getSystemService(ConnectivityManager::class.java)
        val caps=cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return settings(ctx).allowsNetwork(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }
    fun approve(ctx:Context,s:BackupConsent) {
        check(CloudAccount.state.value.userId!=null) {"Sign in first"}
        check(prefs(ctx).edit().putBoolean("data",s.data).putBoolean("music",s.music).putBoolean("automatic",s.automatic).putBoolean("mobile",s.mobile).commit()) {"Could not save permission"}
        val scheduler=ctx.getSystemService(JobScheduler::class.java)
        scheduler.cancel(AUTOMATIC_JOB);scheduler.cancel(MANUAL_JOB)
        progress.value=BackupProgress(message=if(s.data||s.music) "Backup permission saved" else "Backup is off")
        if(s.automatic && (s.data||s.music))schedule(ctx,true)
    }
    fun init(ctx:Context) {scope.launch {
        CloudAccount.state.filter {it.status!="Initializing"}.map {it.userId}.distinctUntilChanged().collect { owner ->
            val scheduler=ctx.getSystemService(JobScheduler::class.java)
            for(id in listOf(AUTOMATIC_JOB,MANUAL_JOB)) {
                val existing=scheduler.getPendingJob(id)
                if(existing!=null && (owner==null || existing.extras.getString("owner")!=owner))scheduler.cancel(id)
            }
            val s=settings(ctx)
            if(runningOwner==null)progress.value=BackupProgress(message=if(owner==null) "Sign in for online backup" else prefs(ctx).getString("result","Choose what to back up").orEmpty())
            if(owner!=null && s.automatic && (s.data||s.music) && scheduler.getPendingJob(AUTOMATIC_JOB)==null)schedule(ctx,true)
        }
    } }
    fun schedule(ctx:Context,automatic:Boolean=false) {
        val owner=CloudAccount.state.value.userId ?: error("Sign in first")
        val s=settings(ctx);check(s.data||s.music) {"Choose library data or music files first"}
        val extras=PersistableBundle().apply {putString("owner",owner);putBoolean("automatic",automatic)}
        val builder=JobInfo.Builder(if(automatic)AUTOMATIC_JOB else MANUAL_JOB,ComponentName(ctx,CloudBackupJob::class.java)).setExtras(extras).setPersisted(true).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        if(s.mobile)builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        else if(Build.VERSION.SDK_INT>=28)builder.setRequiredNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
        else builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        if(automatic)builder.setPeriodic(15*60*1000L)
        check(ctx.getSystemService(JobScheduler::class.java).schedule(builder.build())==JobScheduler.RESULT_SUCCESS) {"Android could not schedule backup. Try again."}
        progress.value=BackupProgress(message=if(networkAllowed(ctx)) "Backup scheduled" else if(s.mobile) "Waiting for a network" else "Waiting for Wi-Fi")
    }
    fun stop(ctx:Context) {val js=ctx.getSystemService(JobScheduler::class.java);js.cancel(MANUAL_JOB);js.cancel(AUTOMATIC_JOB);progress.value=progress.value.copy(busy=false,message="Backup paused; saved files remain")}
    fun result(ctx:Context,text:String) {prefs(ctx).edit().putString("result",text).apply()}
}
