package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus
import kotlinx.coroutines.runBlocking
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
        val saveDir = driveManager.itemDir(feedItem.path) ?: return false

        store.putAll(listOf(DownloadRecord(id, DownloadStatus.DOWNLOADING, null, 0f, feedItem.sizeBytes)))
        return try {
            var lastPersisted = 0f
            val file = engine.download(feedItem.link, saveDir) { p ->
                // Throttle DataStore writes: persist at most every ~1% (and at completion).
                if (p - lastPersisted >= 0.01f || p >= 1f) {
                    lastPersisted = p
                    runBlocking {
                        store.putAll(listOf(DownloadRecord(id, DownloadStatus.DOWNLOADING, null, p, feedItem.sizeBytes)))
                    }
                }
            }
            store.putAll(listOf(DownloadRecord(id, DownloadStatus.READY, file.absolutePath, 1f, feedItem.sizeBytes)))
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
