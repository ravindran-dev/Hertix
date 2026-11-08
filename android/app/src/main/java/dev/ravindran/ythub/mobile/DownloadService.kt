package dev.ravindran.ythub.mobile

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitoring = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); channels(this) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(7, notification("Preparing your downloads"))
        DownloadStore.startQueue()
        if (!monitoring) {
            monitoring = true
            scope.launch {
                while (DownloadStore.hasWork()) {
                    val active = DownloadStore.items.value.count { it.working }
                    getSystemService(NotificationManager::class.java).notify(7, notification("$active active · Download. Convert. Manage."))
                    delay(1500)
                }
                monitoring = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String) = NotificationCompat.Builder(this, "downloads")
        .setSmallIcon(R.drawable.hertix_notification).setContentTitle("Hertix")
        .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openIntent(this)).build()
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadStore.items.value.filter { it.working || it.state == "Queued" }.forEach { DownloadStore.pause(it.id) }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        fun channels(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
                NotificationChannel("downloads", "Active downloads", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel("completed", "Download results", NotificationManager.IMPORTANCE_DEFAULT)))
        }
        private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun notifyFinished(context: Context, item: DownloadItem) {
            if (!DownloadStore.prefs.value.notifications || (item.audio && item.media.playlist && !MusicRepository.state.value.settings.notifyPlaylist)) return
            channels(context)
            runCatching { context.getSystemService(NotificationManager::class.java).notify(item.id.hashCode(), NotificationCompat.Builder(context, "completed")
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Download complete")
                .setContentText(item.media.title).setContentIntent(openIntent(context)).setAutoCancel(true).apply {
                    if (item.audio && item.uris.isNotEmpty()) addAction(android.R.drawable.ic_media_play, "Play now", PendingIntent.getActivity(context, item.id.hashCode(), Intent(context, MainActivity::class.java).putExtra("play_download", item.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }.build()) }
        }
    }
}
