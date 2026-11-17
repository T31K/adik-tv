package com.arflix.tv.megaflix

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.arflix.tv.data.model.DownloadStatus
import com.arflix.tv.player.PlaybackState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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

/** Thrown when a drive copy is cut short because playback started. */
private class MoveAbortedException : IOException("move paused for playback")

/**
 * Drive-safe download queue. While a movie plays, the USB drive serves only that file:
 *
 * - A torrent downloads into internal storage (`filesDir/dl/<id>/`) when there is room
 *   for it plus [INTERNAL_HEADROOM_BYTES]; otherwise into `<drive>/Megaflix/.dl_<id>/`.
 * - Internal downloads may run during playback (they never touch the drive). New drive
 *   downloads only start while nothing is playing (a running torrent is never
 *   interrupted — see the TorrentEngine crash note).
 * - A finished internal download is piece-hash-checked where it sits, recorded as a
 *   pending move ([DownloadRecord.stagedPath]), and copied to the drive only while
 *   nothing plays; a copy cut short by playback restarts once it ends.
 * - The library hash check and the hole scan also wait for playback to end.
 */
@Singleton
class MegaflixDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: TorrentEngine,
    private val store: DownloadStateStore,
    private val driveManager: DriveManager,
    private val syncManager: MegaflixSyncManager,
    private val playbackState: PlaybackState
) {
    private val _activeDownload = MutableStateFlow<ActiveDownload?>(null)
    /** The item torrenting right now (or null when idle). Live speed/ETA. */
    val activeDownload: StateFlow<ActiveDownload?> = _activeDownload.asStateFlow()

    // Staged → drive moves run beside the torrent queue so the next internal
    // download can start while a move waits for playback to end. One copy at a time.
    private val moveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moveMutex = Mutex()
    private val moveJobs = ConcurrentHashMap<String, Job>()

    private val internalRoot: File get() = File(context.filesDir, INTERNAL_DIR)

    private fun internalTempDir(id: String) = File(internalRoot, id)
    private fun driveTempDir(mediaDir: File, id: String) = File(mediaDir, ".dl_$id")

    private fun internalFreeBytes(): Long =
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)

    /**
     * Where this item's torrent data goes. A partial already in one place resumes
     * there; otherwise internal storage when it fits with headroom, else the drive.
     */
    private fun chooseTempDir(id: String, mediaDir: File, sizeBytes: Long?): File {
        val internal = internalTempDir(id)
        val drive = driveTempDir(mediaDir, id)
        if (internal.isDirectory) return internal
        if (drive.isDirectory) return drive
        val size = sizeBytes?.takeIf { it > 0L } ?: return drive
        return if (internalFreeBytes() >= size + INTERNAL_HEADROOM_BYTES) internal else drive
    }

    private fun isInternal(dir: File): Boolean =
        dir.absolutePath.startsWith(internalRoot.absolutePath)

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
        // Per-item temp folder so the "largest video" we pick is THIS torrent's file only.
        val tmpDir = chooseTempDir(id, mediaDir, feedItem.sizeBytes)
        val internal = isInternal(tmpDir)
        if (!internal && playbackState.isPlaying.value) {
            // A drive download would compete with the movie on screen: hold it.
            Log.i(TAG, "queue: $id needs the drive; waiting for playback to end")
            playbackState.awaitIdle()
        }
        tmpDir.mkdirs()
        Log.i(TAG, "queue: $id -> ${if (internal) "internal" else "drive"} temp ${tmpDir.absolutePath}")

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
                is DownloadOutcome.Completed ->
                    if (internal) stageInternal(id, feedItem, linkIndex, tmpDir, outcome)
                    else finalizeFromDrive(id, feedItem, linkIndex, mediaDir, tmpDir, outcome)
                is DownloadOutcome.Interrupted -> when (outcome.mode) {
                    // PAUSE: keep the partial data so Start can resume it.
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
            Log.w(TAG, "download: $id failed", t)
            store.putAll(listOf(DownloadRecord(id, DownloadStatus.FAILED, null, 0f, feedItem.sizeBytes, linkIndex)))
            true // keep draining; the failed item will retry on a later cycle
        } finally {
            _activeDownload.value = null
        }
    }

    /** Drive temp download finished: land it on the flat name, then hash-check it there. */
    private suspend fun finalizeFromDrive(
        id: String,
        feedItem: FeedItemDto,
        linkIndex: Int,
        mediaDir: File,
        tmpDir: File,
        outcome: DownloadOutcome.Completed
    ) {
        // The extension follows the file's real container (an MKV lands as .mkv even
        // if the feed path says .mp4). finalizeToFlat throws if it can't land it.
        val target = flatTargetFor(mediaDir, feedItem.path, outcome.file)
        val finalFile = finalizeToFlat(outcome.file, target)
        tmpDir.deleteRecursively()
        val manifest = outcome.manifest
        if (manifest != null) {
            playbackState.awaitIdle()
            val check = PieceVerifier.verify(finalFile, manifest)
            if (!check.healthy) {
                Log.w(TAG, "hashcheck: ${finalFile.name} FAILED (${check.badPieces}/${check.checkedPieces} bad pieces, sizeOk=${check.sizeMatches})")
                finalFile.delete()
                store.putAll(listOf(DownloadRecord(id, DownloadStatus.FAILED, null, 0f, feedItem.sizeBytes, linkIndex)))
                return
            }
            Log.w(TAG, "hashcheck: ${finalFile.name} healthy (${check.checkedPieces} pieces)")
            store.markHealthy(VideoIntegrity.fingerprint(finalFile))
        }
        store.putAll(listOf(DownloadRecord(id, DownloadStatus.READY, finalFile.absolutePath, 1f, feedItem.sizeBytes, linkIndex)))
    }

    /**
     * Internal download finished: hash-check it where it sits (internal flash, no
     * drive I/O), record the pending move, and hand it to the mover.
     */
    private suspend fun stageInternal(
        id: String,
        feedItem: FeedItemDto,
        linkIndex: Int,
        tmpDir: File,
        outcome: DownloadOutcome.Completed
    ) {
        val manifest = outcome.manifest
        var healthy = false
        if (manifest != null) {
            val check = PieceVerifier.verify(outcome.file, manifest)
            if (!check.healthy) {
                Log.w(TAG, "hashcheck: ${outcome.file.name} FAILED in internal storage (${check.badPieces}/${check.checkedPieces} bad pieces, sizeOk=${check.sizeMatches})")
                tmpDir.deleteRecursively()
                store.putAll(listOf(DownloadRecord(id, DownloadStatus.FAILED, null, 0f, feedItem.sizeBytes, linkIndex)))
                return
            }
            Log.w(TAG, "hashcheck: ${outcome.file.name} healthy in internal storage (${check.checkedPieces} pieces)")
            healthy = true
        }
        store.putAll(listOf(
            DownloadRecord(
                id, DownloadStatus.DOWNLOADING, null, 1f, feedItem.sizeBytes, linkIndex,
                stagedPath = outcome.file.absolutePath, stagedHealthy = healthy
            )
        ))
        scheduleMove(id)
    }

    /** Queues the staged → drive move for [id] (no-op if one is already pending). */
    private fun scheduleMove(id: String) {
        if (moveJobs[id]?.isActive == true) return
        val job = moveScope.launch {
            try {
                moveMutex.withLock { runCatching { moveStagedToDrive(id) }.onFailure { Log.w(TAG, "move: $id failed", it) } }
            } finally {
                moveJobs.remove(id)
            }
        }
        moveJobs[id] = job
    }

    /**
     * Copies a staged internal file to its flat drive name — only while nothing is
     * playing. If playback starts mid-copy the partial `.part` is dropped and the
     * copy restarts after playback ends.
     */
    private suspend fun moveStagedToDrive(id: String) {
        while (true) {
            playbackState.awaitIdle()
            val rec = store.all()[id] ?: return
            val stagedPath = rec.stagedPath ?: return
            val staged = File(stagedPath)
            val feedItem = syncManager.feedItems.value.firstOrNull { it.id == id }
            if (feedItem == null) {
                if (syncManager.feedItems.value.isEmpty()) return // feed not loaded yet; recovery retries
                Log.i(TAG, "move: $id no longer in feed, dropping staged file")
                internalTempDir(id).deleteRecursively()
                return
            }
            if (!staged.isFile) {
                Log.w(TAG, "move: staged file for $id is gone, re-queueing")
                internalTempDir(id).deleteRecursively()
                store.putAll(listOf(rec.copy(status = DownloadStatus.COMING_SOON, progress = 0f, stagedPath = null, stagedHealthy = false)))
                return
            }
            val mediaDir = driveManager.mediaDir()?.takeIf { it.isDirectory || it.mkdirs() }
                ?: return // drive missing; recovery retries on the next drain
            val target = flatTargetFor(mediaDir, feedItem.path, staged)
            val finalFile = try {
                finalizeToFlat(staged, target) { playbackState.isPlaying.value }
            } catch (e: MoveAbortedException) {
                Log.i(TAG, "move: ${target.name} paused for playback; will restart")
                continue
            }
            internalTempDir(id).deleteRecursively()
            if (rec.stagedHealthy) store.markHealthy(VideoIntegrity.fingerprint(finalFile))
            store.putAll(listOf(
                DownloadRecord(id, DownloadStatus.READY, finalFile.absolutePath, 1f, feedItem.sizeBytes, rec.linkIndex)
            ))
            Log.i(TAG, "move: ${finalFile.name} on the drive")
            return
        }
    }

    /** Sequentially download everything queued, one at a time. */
    suspend fun drain() {
        // Self-heal first: re-queue any download left in a temp folder (killed
        // mid-torrent) and resume pending drive moves. Then drain the queue.
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
        // Keep the foreground service alive until staged files are on the drive.
        moveJobs.values.toList().joinAll()
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
     * are never starved, and only while nothing plays. Returns false when there's
     * nothing left to check.
     */
    private suspend fun hashCheckNextUnverified(): Boolean {
        val feed = syncManager.feedItems.value
        if (feed.isEmpty()) return false // can't resolve magnets until the feed has synced
        playbackState.awaitIdle()
        val (rec, file) = unverifiedReady().firstOrNull() ?: return false
        val fp = VideoIntegrity.fingerprint(file)
        val magnets = feed.firstOrNull { it.id == rec.id }?.magnets().orEmpty()
        val order = magnets.indices.sortedBy { if (it == rec.linkIndex) -1 else it }
        val tmp = File(context.cacheDir, "meta_${rec.id}")
        for (i in order) {
            val manifest = engine.fetchManifest(magnets[i], METADATA_TIMEOUT_SEC, tmp) ?: continue
            if (manifest.fileSize != file.length()) continue // a different release of this title
            playbackState.awaitIdle()
            val check = PieceVerifier.verify(file, manifest) { playbackState.isPlaying.value }
            if (check.aborted) {
                Log.i(TAG, "hashcheck: ${file.name} paused for playback; will retry")
                return true
            }
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

    private fun flatTargetFor(mediaDir: File, feedPath: String, src: File): File {
        val ext = VideoContainer.sniffExtension(src) ?: src.extension.lowercase().ifBlank { null }
        val name = if (ext != null) VideoContainer.withExtension(feedPath, ext) else feedPath
        return File(mediaDir, name)
    }

    /**
     * Move [src] to the flat [target], robustly. `renameTo` alone silently returns
     * false for a cross-directory move on the TV's USB FUSE/FAT32 mount (and always
     * fails from internal storage to the drive). So: try the fast rename, and on
     * failure copy into a sibling `.part` in the destination dir (an intra-dir rename
     * that always works), fsync, size-check, then swap it in. [shouldAbort] is polled
     * during the copy; when it fires the `.part` is deleted and [MoveAbortedException]
     * is thrown. Throws IOException on genuine failure so the caller marks it FAILED.
     */
    private fun finalizeToFlat(src: File, target: File, shouldAbort: () -> Boolean = { false }): File {
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
        Log.i(TAG, "finalize: renameTo not possible for ${target.name}; copying")
        val part = File(target.parentFile, target.name + ".part")
        if (part.exists()) part.delete()
        try {
            src.inputStream().use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(DEFAULT_COPY_BUFFER)
                    while (true) {
                        if (shouldAbort()) throw MoveAbortedException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.fd.sync()
                }
            }
        } catch (e: IOException) {
            part.delete()
            throw e
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
     * Clean up temp folders left behind — on the drive (`.dl_<id>`) and in internal
     * storage (`filesDir/dl/<id>`). Orphans (an intact flat file already present, or
     * the item gone from the feed) are removed; finished internal files waiting to be
     * moved get their move resumed; everything else goes back to the queue, where
     * libtorrent re-checks the pieces already on disk and fetches only what's missing.
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
        var records = store.all()

        // Pending moves whose staged file vanished (storage cleared): re-download.
        records.values.filter { it.stagedPath != null && !File(it.stagedPath).isFile }.forEach { rec ->
            Log.w(TAG, "recover: staged file for ${rec.id} missing, re-queueing")
            store.putAll(listOf(rec.copy(status = DownloadStatus.COMING_SOON, progress = 0f, stagedPath = null, stagedHealthy = false)))
        }
        records = store.all()

        val driveTemps = mediaDir.listFiles { f -> f.isDirectory && f.name.startsWith(".dl_") }.orEmpty()
            .map { it.name.removePrefix(".dl_") to it }
        val internalTemps = internalRoot.listFiles { f -> f.isDirectory }.orEmpty()
            .map { it.name to it }
        for ((id, dir) in driveTemps + internalTemps) {
            if (id == _activeDownload.value?.id) continue // being downloaded right now
            if (moveJobs[id]?.isActive == true) continue // being moved right now
            val rec = records[id]
            val feedItem = feed.firstOrNull { it.id == id }
            if (feedItem == null) {
                Log.i(TAG, "recover: $id no longer in feed, pruning temp folder")
                dir.deleteRecursively()
                continue
            }
            if (isInternal(dir) && rec?.stagedPath != null) {
                Log.i(TAG, "recover: resuming pending drive move for $id")
                scheduleMove(id)
                continue
            }
            if (rec?.status == DownloadStatus.PAUSED) continue // user paused; leave it
            val li = rec?.linkIndex ?: 0
            val target = VideoContainer.findIn(mediaDir, feedItem.path) ?: File(mediaDir, feedItem.path)
            if (target.exists() && target.length() > 0) {
                if (!runCatching { VideoIntegrity.hasHoles(target) }.getOrDefault(true)) {
                    Log.i(TAG, "recover: intact flat file already present for $id, pruning temp folder")
                    dir.deleteRecursively()
                    store.putAll(listOf(DownloadRecord(id, DownloadStatus.READY, target.absolutePath, 1f, feedItem.sizeBytes, li)))
                    continue
                }
                // A flat file with holes is a bad earlier finalize; the partial in the
                // temp folder is the better copy, so drop the flat one and resume.
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
     * remembered by fingerprint so each file is scanned only once. Waits for
     * playback to end before each file. Returns how many broken files were found.
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
            playbackState.awaitIdle()
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
        if (rec.status != DownloadStatus.READY && rec.stagedPath == null) {
            store.putAll(listOf(rec.copy(status = DownloadStatus.PAUSED)))
        }
    }

    /** Stop an item: halt it and throw away partial data. A later Start re-downloads. */
    suspend fun stop(id: String) {
        if (_activeDownload.value?.id == id) {
            engine.requestStop() // runOnce wipes the temp folder + writes PAUSED
            return
        }
        val rec = store.all()[id]
        if (rec?.stagedPath != null) return // finished; only waiting to be moved
        driveManager.mediaDir()?.let { driveTempDir(it, id).deleteRecursively() }
        internalTempDir(id).deleteRecursively()
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
        if (rec.stagedPath != null) return // already downloaded
        val next = (rec.linkIndex + 1) % magnets.size
        Log.i(TAG, "swap: $id link[${rec.linkIndex}] -> link[$next]/${magnets.size} (stalled)")
        if (_activeDownload.value?.id == id) {
            // Re-queue BEFORE stopping so runOnce's STOP handler leaves it be.
            store.putAll(listOf(rec.copy(status = DownloadStatus.COMING_SOON, progress = 0f, linkIndex = next)))
            engine.requestStop()
        } else {
            driveManager.mediaDir()?.let { driveTempDir(it, id).deleteRecursively() }
            internalTempDir(id).deleteRecursively()
            store.putAll(listOf(rec.copy(status = DownloadStatus.COMING_SOON, progress = 0f, linkIndex = next)))
            runCatching { DownloadService.start(context) }
        }
    }

    /**
     * The poller (run ~every 60s while the app is alive). If nothing's downloading
     * but items are still lacking (or moves/hash checks are pending), kick the drain
     * service.
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
        private const val INTERNAL_DIR = "dl"
        /** Room left on internal storage beyond the item itself before we use it. */
        private const val INTERNAL_HEADROOM_BYTES = 1L * 1024 * 1024 * 1024 // 1 GB
        private const val DEFAULT_COPY_BUFFER = 1 shl 20 // 1 MB
        private const val METADATA_TIMEOUT_SEC = 45
    }
}
