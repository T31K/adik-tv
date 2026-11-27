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
    @Inject lateinit var syncManager: MegaflixSyncManager
    @Inject lateinit var torrentEngine: TorrentEngine
    @Inject lateinit var driveManager: DriveManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One drain at a time: repeated onStartCommand (every sync kicks us, and
    // START_STICKY restarts) must never race a second drain that would re-pick
    // the item the first one is actively downloading.
    private val draining = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Syncing library…"))
        val night = intent?.getBooleanExtra(EXTRA_NIGHT, false) == true
        if (draining.compareAndSet(false, true)) {
            scope.launch {
                // Keep the CPU and Wi-Fi awake while downloading, even if the TV is
                // in standby with the screen off (night window, or turned off
                // mid-download). Released as soon as the queue is done.
                val locks = AwakeLocks.acquire(this@DownloadService)
                try {
                    // Dry run (flag file set over adb): prove night downloads would work,
                    // without downloading anything or touching the drive.
                    if (night && NightProbe.requested()) {
                        NightProbe.run(this@DownloadService, torrentEngine, driveManager.mediaDir())
                        return@launch
                    }
                    // Started at boot / by the night alarm / restarted by the system
                    // before anyone opened ADIK: load the feed first (at night always
                    // re-sync, to pick up the day's new movies). Wi-Fi can take a while.
                    if (night || syncManager.feedItems.value.isEmpty()) {
                        for (attempt in 1..10) {
                            if (syncManager.sync().isSuccess && syncManager.feedItems.value.isNotEmpty()) break
                            android.util.Log.w("DownloadService", "cold start: feed sync attempt $attempt failed, retrying in 30 s")
                            kotlinx.coroutines.delay(30_000)
                        }
                    }
                    downloadManager.drain()
                } finally {
                    locks.release()
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

        const val EXTRA_NIGHT = "night"

        /**
         * Torrent downloads are switched off in the streaming build (movies stream from R2).
         * Every start path (sync, boot, night alarm, heal poller) goes through [start], so this
         * one switch stops all of them. On a TV without a USB drive the downloader fell back to
         * internal storage and filled it (living-room TCL, 2026-11-27).
         */
        const val DOWNLOADS_ENABLED = false

        fun start(context: Context, night: Boolean = false) {
            if (!DOWNLOADS_ENABLED) return
            val intent = Intent(context, DownloadService::class.java).putExtra(EXTRA_NIGHT, night)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
