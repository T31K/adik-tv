package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MegaflixDownloadManager @Inject constructor(
    private val engine: TorrentEngine,
    private val store: DownloadStateStore,
    private val driveManager: DriveManager,
    private val syncManager: MegaflixSyncManager
) {
    /** Downloads the next queued item. Returns false when nothing is queued. */
    suspend fun runOnce(): Boolean {
        val records = store.all()
        val id = DownloadQueue.nextToDownload(records) ?: return false
        val feedItem = syncManager.feedItems.value.firstOrNull { it.id == id } ?: return false
        val mediaDir = driveManager.mediaDir() ?: return false
        // Download into a per-item temp subfolder so the "largest video" we pick is THIS
        // torrent's file only — never another movie already sitting in Megaflix/.
        val tmpDir = File(mediaDir, ".dl_$id")
        tmpDir.mkdirs()

        store.putAll(listOf(DownloadRecord(id, DownloadStatus.DOWNLOADING, null, 0f, feedItem.sizeBytes)))
        return try {
            var lastPersisted = 0f
            val downloaded = engine.download(feedItem.link, tmpDir) { p ->
                // Throttle DataStore writes: persist at most every ~1% (and at completion).
                if (p - lastPersisted >= 0.01f || p >= 1f) {
                    lastPersisted = p
                    runBlocking {
                        store.putAll(listOf(DownloadRecord(id, DownloadStatus.DOWNLOADING, null, p, feedItem.sizeBytes)))
                    }
                }
            }
            // Move the torrent's video out to the exact feed filename, then drop the temp folder.
            val target = File(mediaDir, feedItem.path)
            if (target.exists()) target.delete()
            val moved = downloaded.renameTo(target)
            val finalFile = if (moved) target else downloaded
            if (moved) tmpDir.deleteRecursively()
            store.putAll(listOf(DownloadRecord(id, DownloadStatus.READY, finalFile.absolutePath, 1f, feedItem.sizeBytes)))
            true
        } catch (t: Throwable) {
            store.putAll(listOf(DownloadRecord(id, DownloadStatus.FAILED, null, 0f, feedItem.sizeBytes)))
            true // keep draining; the failed item will retry on a later cycle
        }
    }

    /** Sequentially download everything queued, one at a time. */
    suspend fun drain() {
        while (runOnce()) { /* one at a time */ }
    }
}
