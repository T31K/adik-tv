package com.arflix.tv.megaflix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that drains the Megaflix download queue (torrents feed items
 * onto the USB drive) and stops itself once nothing is left. Started by
 * MegaflixSyncManager after a sync when items are queued and storage access is granted.
 */
@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var downloadManager: MegaflixDownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One drain at a time: repeated onStartCommand (every sync kicks us, and
    // START_STICKY restarts) must never race a second drain that would re-pick
    // the item the first one is actively downloading.
    private val draining = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Syncing library…"))
        if (draining.compareAndSet(false, true)) {
            scope.launch {
                try {
                    downloadManager.drain()
                } finally {
                    draining.set(false)
                    stopSelf()
                }
            }
        }
        // STICKY: if the system kills us mid-drain (RAM pressure while another
        // app is foreground), restart and resume the interrupted download.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADIK TV")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "megaflix_downloads"
        private const val NOTIF_ID = 4201

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
