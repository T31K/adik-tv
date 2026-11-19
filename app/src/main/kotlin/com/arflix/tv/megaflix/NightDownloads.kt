package com.arflix.tv.megaflix

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * Night downloads with the screen off.
 *
 * A wake-up alarm at [START_HOUR] wakes the TV's processor out of standby (the
 * screen stays dark — tested on the Sharp AQUOS: wake-up alarms bring Wi-Fi back
 * while the panel is off) and starts [DownloadService], which syncs the feed and
 * holds the CPU + Wi-Fi awake while it downloads. Until [END_HOUR] the alarm
 * re-fires every [RECHECK_MINUTES] in case the queue finished or the system
 * stopped the service. Rescheduled from app start, boot and each firing.
 */
object NightDownloads {
    const val START_HOUR = 3
    const val END_HOUR = 7
    private const val RECHECK_MINUTES = 30
    private const val TAG = "NightDownloads"
    private const val REQUEST_CODE = 4202

    fun inWindow(now: Calendar = Calendar.getInstance()): Boolean =
        now.get(Calendar.HOUR_OF_DAY) in START_HOUR until END_HOUR

    /** Next firing: in 30 min while inside the window, else the next START_HOUR:00. */
    fun nextTrigger(now: Calendar = Calendar.getInstance()): Long {
        val next = now.clone() as Calendar
        if (inWindow(now)) {
            next.add(Calendar.MINUTE, RECHECK_MINUTES)
            if (inWindow(next)) return next.timeInMillis
            next.timeInMillis = now.timeInMillis
        }
        next.set(Calendar.HOUR_OF_DAY, START_HOUR)
        next.set(Calendar.MINUTE, 0)
        next.set(Calendar.SECOND, 0)
        next.set(Calendar.MILLISECOND, 0)
        if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis
    }

    fun scheduleNext(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, NightDownloadReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = nextTrigger()
        runCatching {
            // Exact + allow-while-idle wakes the device from standby, and an exact
            // alarm also lets the receiver start the foreground download service.
            val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            Log.w(TAG, "next night check at ${java.util.Date(at)} (exact=$exactOk)")
        }.onFailure { Log.w(TAG, "could not schedule night downloads", it) }
    }
}

/**
 * CPU + Wi-Fi kept awake while the download queue runs, so a TV put into standby
 * (screen off) keeps downloading instead of suspending mid-torrent. Capped at
 * [MAX_HOLD_MS] as a safety net; always released when the queue finishes.
 */
class AwakeLocks private constructor(
    private val wake: android.os.PowerManager.WakeLock?,
    private val wifi: android.net.wifi.WifiManager.WifiLock?
) {
    fun release() {
        runCatching { if (wake?.isHeld == true) wake.release() }
        runCatching { if (wifi?.isHeld == true) wifi.release() }
    }

    companion object {
        private const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L

        fun acquire(context: Context): AwakeLocks {
            val app = context.applicationContext
            val wake = runCatching {
                app.getSystemService(android.os.PowerManager::class.java)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "adik:downloads")
                    .apply { setReferenceCounted(false); acquire(MAX_HOLD_MS) }
            }.getOrNull()
            @Suppress("DEPRECATION")
            val wifi = runCatching {
                app.getSystemService(android.net.wifi.WifiManager::class.java)
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "adik:downloads")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            return AwakeLocks(wake, wifi)
        }
    }
}

/** Fires during the night window: schedule the next check, then start the queue. */
class NightDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NightDownloads.scheduleNext(context)
        if (!NightDownloads.inWindow()) return
        if (!StoragePermission.hasAllFilesAccess()) return
        runCatching { DownloadService.start(context, night = true) }
            .onSuccess { Log.w("NightDownloads", "night window: download service started") }
            .onFailure { Log.w("NightDownloads", "night window: could not start download service", it) }
    }
}
