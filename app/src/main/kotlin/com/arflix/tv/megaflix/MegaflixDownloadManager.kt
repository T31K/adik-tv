package com.arflix.tv.megaflix

import android.content.Context
import android.util.Log
import com.arflix.tv.data.model.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val engine: TorrentEngine,
    private val store: DownloadStateStore,
    private val driveManager: DriveManager,
    private val syncManager: MegaflixSyncManager
) {
    private val _activeDownload = MutableStateFlow<ActiveDownload?>(null)
    /** The item torrenting right now (or null when idle). Live speed/ETA. */
    val activeDownload: StateFlow<ActiveDownload?> = _activeDownload.asStateFlow()

    // Stall-poller bookkeeping (see pollAndHeal): last progress seen and how many
    // consecutive polls the active item has been crawling.
    private var lastPollProgress: Float? = null
    private var pollStrikes = 0

    /** Downloads the next queued item. Returns false when nothing is queued. */
    suspend fun runOnce(): Boolean {
        val records = store.all()
        val id = DownloadQueue.nextToDownload(records) ?: return false
        val feedItem = syncManager.feedItems.value.firstOrNull { it.id == id } ?: return false
        val mediaDir = driveManager.mediaDir() ?: return false
        // Which ranked magnet to use — a stall swap advances linkIndex on the record.
        val magnets = feedItem.magnets()
        val linkIndex = (records[id]?.linkIndex ?: 0).coerceIn(0, (magnets.size - 1).coerceAtLeast(0))
        val magnet = magnets.getOrNull(linkIndex) ?: return false
        // Download into a per-item temp subfolder so the "largest video" we pick is THIS
        // torrent's file only — never another movie already sitting in Megaflix/.
        val tmpDir = File(mediaDir, ".dl_$id")
        tmpDir.mkdirs()

        store.putAll(listOf(DownloadRecord(id, DownloadStatus.DOWNLOADING, null, 0f, feedItem.sizeBytes, linkIndex)))
        _activeDownload.value = ActiveDownload(id, 0f, 0L, 0L, null, 0)
        return try {
            var lastPersisted = 0f
            var lastProgress = 0f
            val outcome = engine.download(magnet, tmpDir) { tp ->
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
                        store.putAll(listOf(DownloadRecord(id, DownloadStatus.DOWNLOADING, null, tp.progress, feedItem.sizeBytes, linkIndex)))
                    }
                }
            }
            when (outcome) {
                is DownloadOutcome.Completed -> {
                    // Move the torrent's video out to the flat feed filename — with the
                    // extension its contents call for (an MKV release lands as .mkv even
                    // if the feed path says .mp4) — then drop the temp folder.
                    // finalizeToFlat throws if it can't land the file (→ FAILED).
                    val target = flatTargetFor(mediaDir, feedItem.path, outcome.file)
                    val finalFile = finalizeToFlat(outcome.file, target)
                    tmpDir.deleteRecursively()
                    // Thorough check of the file where it will actually be played from:
                    // re-hash every piece against the torrent. Only a full match is
                    // flagged healthy; a mismatch goes back to FAILED for a fresh retry.
                    val manifest = outcome.manifest
                    if (manifest != null) {
                        val check = PieceVerifier.verify(finalFile, manifest)
                        if (!check.healthy) {
                            Log.w(TAG, "hashcheck: ${finalFile.name} FAILED (${check.badPieces}/${check.checkedPieces} bad pieces, sizeOk=${check.sizeMatches})")
                            finalFile.delete()
                            store.putAll(listOf(DownloadRecord(id, DownloadStatus.FAILED, null, 0f, feedItem.sizeBytes, linkIndex)))
                            return true
                        }
                        Log.w(TAG, "hashcheck: ${finalFile.name} healthy (${check.checkedPieces} pieces)")
                        store.markHealthy(VideoIntegrity.fingerprint(finalFile))
                    }
                    store.putAll(listOf(DownloadRecord(id, DownloadStatus.READY, finalFile.absolutePath, 1f, feedItem.sizeBytes, linkIndex)))
                }
                is DownloadOutcome.Interrupted -> when (outcome.mode) {
                    // PAUSE: keep the partial data in .dl_<id> so Start can resume it.
                    Interruption.PAUSE ->
                        store.putAll(listOf(DownloadRecord(id, DownloadStatus.PAUSED, null, lastProgress, feedItem.sizeBytes, linkIndex)))
                    // STOP: discard the partial data. But a magnet swap also stops the
                    // torrent after re-queueing the record as COMING_SOON with the next
                    // linkIndex — don't clobber that. Only a genuine user Stop (record
                    // still DOWNLOADING here) becomes PAUSED.
                    Interruption.STOP -> {
                        tmpDir.deleteRecursively()
                        if (store.all()[id]?.status == DownloadStatus.DOWNLOADING) {
                            store.putAll(listOf(DownloadRecord(id, DownloadStatus.PAUSED, null, 0f, feedItem.sizeBytes, linkIndex)))
                        }
                    }
                }
            }
            true
        } catch (t: Throwable) {
            store.putAll(listOf(DownloadRecord(id, DownloadStatus.FAILED, null, 0f, feedItem.sizeBytes, linkIndex)))
            true // keep draining; the failed item will retry on a later cycle
        } finally {
            _activeDownload.value = null
        }
    }

    /** Sequentially download everything queued, one at a time. */
    suspend fun drain() {
        // Self-heal first: re-queue any download left in its .dl_<id> temp folder
        // (killed mid-torrent or mid-move) so the queue re-checks and finishes it.
        // Then drain the queue.
        runCatching { recoverStrandedDownloads() }
            .onFailure { Log.w(TAG, "recover: pass failed", it) }
        // Downloads first; when the queue is empty, hash-check one older movie and
        // loop (a broken one is re-queued, so it downloads before the next check).
        while (true) {
            while (runOnce()) { /* one at a time */ }
            val checked = runCatching { hashCheckNextUnverified() }
                .onFailure { Log.w(TAG, "hashcheck: pass failed", it) }
                .getOrDefault(false)
            if (!checked) break
        }
    }

    // Files the library check couldn't match to any of the item's magnets (dead
    // magnets, pre-seeded drive copies). Skipped until the next app start.
    private val uncheckable = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** READY files on the drive that haven't passed the torrent hash check yet. */
    private suspend fun unverifiedReady(): List<Pair<DownloadRecord, File>> {
        val healthy = store.healthyFiles()
        return store.all().values
            .filter { it.status == DownloadStatus.READY }
            .mapNotNull { rec -> rec.localFilePath?.let(::File)?.takeIf { it.isFile }?.let { rec to it } }
            .filter { (_, f) -> VideoIntegrity.fingerprint(f).let { it !in healthy && it !in uncheckable } }
    }

    /** True when the one-time library hash check still has work to do. */
    suspend fun hasUnverified(): Boolean =
        syncManager.feedItems.value.isNotEmpty() && unverifiedReady().isNotEmpty()

    /**
     * One-time library check for movies downloaded before hashes were kept: fetch
     * the item's torrent metadata (trying the magnet it was downloaded with first,
     * matched by exact file size), re-hash the file on the drive, and flag it
     * healthy — or delete and re-queue it. Checks ONE file per call so downloads
     * are never starved. Returns false when there's nothing left to check.
     */
    private suspend fun hashCheckNextUnverified(): Boolean {
        val feed = syncManager.feedItems.value
        if (feed.isEmpty()) return false // can't resolve magnets until the feed has synced
        val (rec, file) = unverifiedReady().firstOrNull() ?: return false
        val fp = VideoIntegrity.fingerprint(file)
        val magnets = feed.firstOrNull { it.id == rec.id }?.magnets().orEmpty()
        val order = magnets.indices.sortedBy { if (it == rec.linkIndex) -1 else it }
        val tmp = File(context.cacheDir, "meta_${rec.id}")
        for (i in order) {
            val manifest = engine.fetchManifest(magnets[i], METADATA_TIMEOUT_SEC, tmp) ?: continue
            if (manifest.fileSize != file.length()) continue // a different release of this title
            val check = PieceVerifier.verify(file, manifest)
            if (check.healthy) {
                Log.w(TAG, "hashcheck: ${file.name} healthy (${check.checkedPieces} pieces, link[$i])")
                store.markHealthy(fp)
            } else {
                Log.w(TAG, "hashcheck: ${file.name} BROKEN (${check.badPieces}/${check.checkedPieces} bad pieces), re-queueing ${rec.id}")
                if (!file.delete()) Log.w(TAG, "hashcheck: could not delete ${file.absolutePath}")
                store.putAll(listOf(rec.copy(status = DownloadStatus.COMING_SOON, localFilePath = null, progress = 0f, linkIndex = i)))
            }
            return true
        }
        Log.w(TAG, "hashcheck: ${file.name} — no magnet matched its size, can't verify")
        uncheckable += fp
        return true
    }

    /**
     * Move [src] to the flat [target], robustly. `renameTo` alone silently returns
     * false for a cross-directory move on the TV's USB FUSE/FAT32 mount, which used
     * to strand fully-downloaded files inside their temp folder. So: try the fast
     * rename, and on failure copy into a sibling `.part` in the destination dir
     * (an intra-dir rename that always works), fsync, size-check, then swap it in.
     * Throws on genuine failure so the caller marks the item FAILED.
     */
    private fun flatTargetFor(mediaDir: File, feedPath: String, src: File): File {
        val ext = VideoContainer.sniffExtension(src) ?: src.extension.lowercase().ifBlank { null }
        val name = if (ext != null) VideoContainer.withExtension(feedPath, ext) else feedPath
        return File(mediaDir, name)
    }

    private fun finalizeToFlat(src: File, target: File): File {
        target.parentFile?.mkdirs()
        // Clear the slot, including an older copy under another extension (foo.mp4 for foo.mkv).
        target.parentFile?.let { dir ->
            while (true) {
                val old = VideoContainer.findIn(dir, target.name) ?: break
                if (!old.delete()) break
            }
        }
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
     * Clean up any `.dl_<id>` temp folder left behind. Orphans (an intact flat file
     * already present, or the item gone from the feed) are removed; everything
     * else goes back to the queue, where libtorrent re-checks the pieces already
     * on disk and fetches only what's missing — a stranded torrent that really was
     * complete finishes in seconds and lands via the normal finalize path.
     *
     * Completeness is NEVER judged by file length here: libtorrent allocates each
     * file at full size up front, so a download killed at 60% (the 2026-09-20
     * SIGSEGVs) already "looks" complete. Finalizing those by size is what
     * shipped Grand Budapest Hotel / Mad Max with ~40% of their pieces missing.
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
            val rec = records[id]
            val li = rec?.linkIndex ?: 0
            val target = VideoContainer.findIn(mediaDir, feedItem.path) ?: File(mediaDir, feedItem.path)
            if (target.exists() && target.length() > 0) {
                if (!runCatching { VideoIntegrity.hasHoles(target) }.getOrDefault(true)) {
                    Log.i(TAG, "recover: intact flat file already present for $id, pruning temp folder")
                    dir.deleteRecursively()
                    store.putAll(listOf(DownloadRecord(id, DownloadStatus.READY, target.absolutePath, 1f, feedItem.sizeBytes, li)))
                    continue
                }
                // A flat file with holes is a bad earlier finalize; the partial in
                // .dl_<id> is the better copy, so drop the flat one and resume.
                Log.w(TAG, "recover: flat file for $id has holes, deleting it and resuming the partial")
                target.delete()
            }
            // Hand it back to the queue (a wrongly-READY or missing record would
            // otherwise never be picked up again).
            if (rec == null || rec.status == DownloadStatus.READY) {
                Log.i(TAG, "recover: re-queueing stranded partial $id")
                store.putAll(listOf(DownloadRecord(id, DownloadStatus.COMING_SOON, null, 0f, feedItem.sizeBytes, li)))
            }
        }
    }

    /**
     * Check every READY movie for zero-filled holes (see [VideoIntegrity]) — heals
     * the files the old length-based recovery finalized half-downloaded. A broken
     * file is deleted and re-queued for a fresh download; intact ones are
     * remembered by fingerprint so each file is scanned only once. Returns how
     * many broken files were found.
     */
    suspend fun verifyReadyFiles(): Int {
        val verified = store.verifiedFiles()
        val stillVerified = mutableSetOf<String>()
        var broken = 0
        for (rec in store.all().values) {
            if (rec.status != DownloadStatus.READY) continue
            val file = rec.localFilePath?.let(::File) ?: continue
            if (!file.isFile) continue
            val fp = VideoIntegrity.fingerprint(file)
            if (fp in verified) {
                stillVerified += fp
                continue
            }
            // Unreadable right now (drive remounting): leave it for the next pass.
            val holes = runCatching { VideoIntegrity.hasHoles(file) }.getOrNull() ?: continue
            if (!holes) {
                stillVerified += fp
                continue
            }
            Log.w(TAG, "verify: ${file.name} has missing pieces, deleting and re-queueing ${rec.id}")
            if (!file.delete()) Log.w(TAG, "verify: could not delete ${file.absolutePath}")
            store.putAll(listOf(rec.copy(status = DownloadStatus.COMING_SOON, localFilePath = null, progress = 0f)))
            broken++
        }
        store.setVerifiedFiles(stillVerified)
        if (broken > 0 && StoragePermission.hasAllFilesAccess()) {
            runCatching { DownloadService.start(context) }
        }
        return broken
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
        store.putAll(listOf(DownloadRecord(id, DownloadStatus.PAUSED, null, 0f, rec?.sizeBytes, rec?.linkIndex ?: 0)))
    }

    /** Start (or resume) an item: make it eligible for the drainer again. */
    suspend fun start(id: String) {
        val rec = store.all()[id] ?: return
        if (rec.status == DownloadStatus.READY || rec.status == DownloadStatus.DOWNLOADING) return
        // Keep any partial progress from a PAUSE so libtorrent can continue it.
        store.putAll(listOf(rec.copy(status = DownloadStatus.COMING_SOON)))
        runCatching { DownloadService.start(context) }
    }

    // ── Stall poller + magnet swap (baked-list resilience) ──────────────────

    /**
     * Move an item onto its next ranked magnet (a better-seeded alternate), wipe
     * the partial (different infohash), and re-queue it. For the item torrenting
     * right now, re-queue first then stop the torrent so runOnce doesn't clobber
     * the COMING_SOON re-queue with a PAUSED (see the STOP handler in runOnce).
     */
    suspend fun swapToNextMagnet(id: String) {
        val feedItem = syncManager.feedItems.value.firstOrNull { it.id == id } ?: return
        val magnets = feedItem.magnets()
        if (magnets.size <= 1) return // no alternate to swap to
        val rec = store.all()[id] ?: return
        val next = (rec.linkIndex + 1) % magnets.size
        Log.i(TAG, "swap: $id link[${rec.linkIndex}] -> link[$next]/${magnets.size} (stalled)")
        if (_activeDownload.value?.id == id) {
            // Re-queue BEFORE stopping so runOnce's STOP handler leaves it be.
            store.putAll(listOf(rec.copy(status = DownloadStatus.COMING_SOON, progress = 0f, linkIndex = next)))
            engine.requestStop()
        } else {
            driveManager.mediaDir()?.let { File(it, ".dl_$id").deleteRecursively() }
            store.putAll(listOf(rec.copy(status = DownloadStatus.COMING_SOON, progress = 0f, linkIndex = next)))
            runCatching { DownloadService.start(context) }
        }
    }

    /**
     * The poller (run ~every 60s while the app is alive). If the active download
     * is crawling with no real progress across two polls, swap it to the next
     * magnet. If nothing's downloading but items are still lacking, kick the
     * drain service. This is what unwedges a dead/slow torrent hogging the queue.
     */
    suspend fun pollAndHeal() {
        // Auto-swap is DISABLED alongside the interrupt (see TorrentEngine): stopping
        // a live torrent SIGSEGVs, and swapping without a working stop only re-queues
        // the record while the download keeps running — which wedges the drain and
        // makes the tab read "0 downloading". So the poller no longer swaps; it just
        // re-kicks the drain when it's idle with work still pending.
        if (_activeDownload.value != null) return
        val lacking = store.all().values.any {
            it.status == DownloadStatus.COMING_SOON ||
                it.status == DownloadStatus.FAILED ||
                it.status == DownloadStatus.DOWNLOADING
        }
        if ((lacking || hasUnverified()) && StoragePermission.hasAllFilesAccess()) {
            runCatching { DownloadService.start(context) }
        }
    }

    private fun etaSeconds(totalBytes: Long?, downloadedBytes: Long, rateBps: Long): Long? {
        if (rateBps <= 0L || totalBytes == null || totalBytes <= 0L) return null
        val remaining = (totalBytes - downloadedBytes).coerceAtLeast(0L)
        return remaining / rateBps
    }

    companion object {
        private const val TAG = "MegaflixDownload"
        private const val DEFAULT_COPY_BUFFER = 1 shl 20 // 1 MB
        private const val METADATA_TIMEOUT_SEC = 45
        private const val STALL_BPS = 30L * 1024 // under 30 KB/s counts as crawling
        private const val STALL_STRIKES = 2 // consecutive slow polls (~2 min) before a swap
    }
}
