package com.arflix.tv.megaflix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the download queue when the TV boots (or the app is updated), so movies
 * keep downloading even if nobody opens ADIK. [DownloadService] syncs the feed
 * itself when the app hasn't loaded it yet.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_QUICKBOOT_POWERON
        ) return
        // Alarms don't survive a reboot or an app update: re-arm the night window.
        NightDownloads.scheduleNext(context)
        if (!StoragePermission.hasAllFilesAccess()) {
            Log.w(TAG, "boot: no storage access yet, not starting downloads")
            return
        }
        // Android 15+ blocks starting a dataSync foreground service from boot;
        // there the queue starts the next time ADIK is opened instead.
        runCatching { DownloadService.start(context) }
            .onSuccess { Log.w(TAG, "boot: download service started ($action)") }
            .onFailure { Log.w(TAG, "boot: could not start download service ($action)", it) }
    }

    companion object {
        private const val TAG = "MegaflixBoot"
        // Some TV vendors send this instead of BOOT_COMPLETED on a "quick" power-on.
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
