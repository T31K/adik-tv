package com.arflix.tv.megaflix

import android.util.Log
import com.arflix.tv.data.model.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live, in-memory stats for the item currently downloading — never persisted
 * (speed/ETA change every second). The Settings Downloads screen observes this
 * for the MB/s + ETA read-out; the queue/failed/ready lists come from the
 * persisted [DownloadStateStore] records instead.
 */
data class ActiveDownload(
    val id: String,
    val progress: Float,        // 0f..1f
    val speedBps: Long,         // bytes/second
    val downloadedBytes: Long,  // bytes done so far
    val etaSeconds: Long?,      // null when unknown (no rate yet)
    val peers: Int
)

@Singleton
class MegaflixDownloadManager @Inject constructor(
    private val engine: TorrentEngine,
    private val store: DownloadStateStore,
    private val driveManager: DriveManager,
    private val syncManager: MegaflixSyncManager
) {
    private val _activeDownload = MutableStateFlow<ActiveDownload?>(null)
    /** The item torrenting right now (or null when idle). Live speed/ETA. */
    val activeDownload: StateFlow<ActiveDownload?> = _activeDownload.asStateFlow()
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
        _activeDownload.value = ActiveDownload(id, 0f, 0L, 0L, null, 0)
        return try {
            var lastPersisted = 0f
            var lastProgress = 0f
            val outcome = engine.download(feedItem.link, tmpDir) { tp ->
                lastProgress = tp.progress
                // Live, unthrottled: drives the Settings MB/s + ETA read-out.
                _activeDownload.value = ActiveDownload(
                    id = id,
                    progress = tp.progress,
                    speedBps = tp.downloadRateBps,
                    downloadedBytes = tp.downloadedBytes,
                    etaSeconds = etaSeconds(feedItem.sizeBytes, tp.downloadedBytes, tp.downloadRateBps),
                    peers = tp.peers
                )
                // Throttle DataStore writes: persist at most every ~1% (and at completion).
                if (tp.progress - lastPersisted >= 0.01f || tp.progress >= 1f) {
                    lastPersisted = tp.progress
                    runBlocking {
                        store.putAll(listOf(DownloadRecord(id, DownloadStatus.DOWNLOADING, null, tp.progress, feedItem.sizeBytes)))
                    }
                }
            }
            when (outcome) {
                is DownloadOutcome.Completed -> {
                    // Move the torrent's video out to the exact flat feed filename, then drop
                    // the temp folder. finalizeToFlat throws if it can't land the file (→ FAILED).
                    val target = File(mediaDir, feedItem.path)
                    val finalFile = finalizeToFlat(outcome.file, target)
                    tmpDir.deleteRecursively()
                    store.putAll(listOf(DownloadRecord(id, DownloadStatus.READY, finalFile.absolutePath, 1f, feedItem.sizeBytes)))
                }
                is DownloadOutcome.Interrupted -> when (outcome.mode) {
                    // PAUSE: keep the partial data in .dl_<id> so Start can resume it.
                    Interruption.PAUSE ->
                        store.putAll(listOf(DownloadRecord(id, DownloadStatus.PAUSED, null, lastProgress, feedItem.sizeBytes)))
                    // STOP: discard the partial data; a later Start re-downloads from scratch.
                    Interruption.STOP -> {
                        tmpDir.deleteRecursively()
                        store.putAll(listOf(DownloadRecord(id, DownloadStatus.PAUSED, null, 0f, feedItem.sizeBytes)))
                    }
                }
            }
            true
        } catch (t: Throwable) {
            store.putAll(listOf(DownloadRecord(id, DownloadStatus.FAILED, null, 0f, feedItem.sizeBytes)))
            true // keep draining; the failed item will retry on a later cycle
        } finally {
            _activeDownload.value = null
        }
    }

    /** Sequentially download everything queued, one at a time. */
    suspend fun drain() {
        // Self-heal first: finish any download that fetched all its bytes but never
        // got moved out of its .dl_<id> temp folder (e.g. a killed move, or a
        // renameTo the USB FUSE mount refused). Then drain the queue.
        runCatching { recoverStrandedDownloads() }
            .onFailure { Log.w(TAG, "recover: pass failed", it) }
        while (runOnce()) { /* one at a time */ }
    }

    /**
     * Move [src] to the flat [target], robustly. `renameTo` alone silently returns
     * false for a cross-directory move on the TV's USB FUSE/FAT32 mount, which used
     * to strand fully-downloaded files inside their temp folder. So: try the fast
     * rename, and on failure copy into a sibling `.part` in the destination dir
     * (an intra-dir rename that always works), fsync, size-check, then swap it in.
     * Throws on genuine failure so the caller marks the item FAILED.
     */
    private fun finalizeToFlat(src: File, target: File): File {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (src.renameTo(target)) {
            Log.i(TAG, "finalize: renamed ${src.name} -> ${target.name}")
            return target
        }
        Log.w(TAG, "finalize: renameTo failed for ${target.name}; copying instead")
        val part = File(target.parentFile, target.name + ".part")
        if (part.exists()) part.delete()
        src.inputStream().use { input ->
            FileOutputStream(part).use { out ->
                input.copyTo(out, DEFAULT_COPY_BUFFER)
                out.fd.sync()
            }
        }
        if (part.length() != src.length()) {
            part.delete()
            throw IOException("finalize: copy size mismatch for ${target.name} (${part.length()} != ${src.length()})")
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("finalize: could not swap .part into place for ${target.name}")
        }
        src.delete()
        Log.i(TAG, "finalize: copied ${target.name} (${target.length()} bytes)")
        return target
    }

    /**
     * Finish or clean up any `.dl_<id>` temp folder left behind. Complete videos
     * are moved to their flat name and marked READY; orphans (flat file already
     * present, or item gone from the feed) are removed; genuinely partial ones and
     * user-paused ones are left untouched for the normal queue to resume.
     */
    suspend fun recoverStrandedDownloads() {
        val mediaDir = driveManager.mediaDir() ?: return
        val feed = syncManager.feedItems.value
        if (feed.isEmpty()) return // no titles to resolve flat names against yet
        val records = store.all()
        val temps = mediaDir.listFiles { f -> f.isDirectory && f.name.startsWith(".dl_") } ?: return
        for (dir in temps) {
            val id = dir.name.removePrefix(".dl_")
            if (id == _activeDownload.value?.id) continue // being downloaded right now
            if (records[id]?.status == DownloadStatus.PAUSED) continue // user paused; leave it
            val feedItem = feed.firstOrNull { it.id == id }
            if (feedItem == null) {
                Log.i(TAG, "recover: $id no longer in feed, pruning temp folder")
                dir.deleteRecursively()
                continue
            }
            val target = File(mediaDir, feedItem.path)
            if (target.exists() && target.length() > 0) {
                Log.i(TAG, "recover: flat file already present for $id, pruning temp folder")
                dir.deleteRecursively()
                store.putAll(listOf(DownloadRecord(id, DownloadStatus.READY, target.absolutePath, 1f, feedItem.sizeBytes)))
                continue
            }
            val video = dir.walkTopDown()
                .filter { it.isFile && DriveManager.isVideoFile(it.name) }
                .maxByOrNull { it.length() }
                ?: continue
            val expected = feedItem.sizeBytes
            val complete = if (expected != null && expected > 0) {
                video.length() >= (expected * 0.99).toLong()
            } else {
                video.length() > 50L * 1024 * 1024 // no size hint: accept anything non-trivial
            }
            if (!complete) continue // still partial — the queue will resume it
            runCatching {
                val finalFile = finalizeToFlat(video, target)
                dir.deleteRecursively()
                store.putAll(listOf(DownloadRecord(id, DownloadStatus.READY, finalFile.absolutePath, 1f, feedItem.sizeBytes)))
                Log.i(TAG, "recover: finalized stranded download $id")
            }.onFailure { Log.w(TAG, "recover: could not finalize $id", it) }
        }
    }

    // ── Per-item controls (Settings → Downloads) ────────────────────────────
    // "Active" means the item torrenting right now; the rest are just records.

    /** Pause an item: halt it and keep whatever's downloaded. Won't auto-resume. */
    suspend fun pause(id: String) {
        if (_activeDownload.value?.id == id) {
            engine.requestPause() // runOnce writes the PAUSED record when it unwinds
            return
        }
        val rec = store.all()[id] ?: return
        if (rec.status != DownloadStatus.READY) {
            store.putAll(listOf(rec.copy(status = DownloadStatus.PAUSED)))
        }
    }

    /** Stop an item: halt it and throw away partial data. A later Start re-downloads. */
    suspend fun stop(id: String) {
        if (_activeDownload.value?.id == id) {
            engine.requestStop() // runOnce wipes the temp folder + writes PAUSED
            return
        }
        driveManager.mediaDir()?.let { File(it, ".dl_$id").deleteRecursively() }
        val rec = store.all()[id]
        val size = rec?.sizeBytes
        store.putAll(listOf(DownloadRecord(id, DownloadStatus.PAUSED, null, 0f, size)))
    }

    /** Start (or resume) an item: make it eligible for the drainer again. */
    suspend fun start(id: String) {
        val rec = store.all()[id] ?: return
        if (rec.status == DownloadStatus.READY || rec.status == DownloadStatus.DOWNLOADING) return
        // Keep any partial progress from a PAUSE so libtorrent can continue it.
        store.putAll(listOf(rec.copy(status = DownloadStatus.COMING_SOON)))
    }

    private fun etaSeconds(totalBytes: Long?, downloadedBytes: Long, rateBps: Long): Long? {
        if (rateBps <= 0L || totalBytes == null || totalBytes <= 0L) return null
        val remaining = (totalBytes - downloadedBytes).coerceAtLeast(0L)
        return remaining / rateBps
    }

    companion object {
        private const val TAG = "MegaflixDownload"
        private const val DEFAULT_COPY_BUFFER = 1 shl 20 // 1 MB
    }
}
