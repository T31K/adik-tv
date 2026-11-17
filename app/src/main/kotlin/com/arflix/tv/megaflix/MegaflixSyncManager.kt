package com.arflix.tv.megaflix

import android.content.Context
import com.arflix.tv.data.model.DownloadStatus
import com.arflix.tv.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SyncOutcome(val total: Int, val ready: Int, val comingSoon: Int, val removed: Int)

@Singleton
class MegaflixSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedApi: MegaflixFeedApi,
    private val store: DownloadStateStore,
    private val driveManager: DriveManager,
    private val playbackState: com.arflix.tv.player.PlaybackState = com.arflix.tv.player.PlaybackState()
) {
    private val _feedItems = MutableStateFlow<List<FeedItemDto>>(emptyList())
    val feedItems: StateFlow<List<FeedItemDto>> = _feedItems.asStateFlow()

    private val token = Constants.MEGAFLIX_FEED_TOKEN
    private val pageSize = 100

    /** Returns true if the server's rev differs from the last one we synced. */
    suspend fun revChanged(): Boolean = runCatching {
        feedApi.getRev(token, store.getDeviceId()).rev != store.getRev()
    }.getOrDefault(false)

    suspend fun sync(): Result<SyncOutcome> = runCatching {
        val feed = fetchAllPages()
        _feedItems.value = feed

        val existing = store.all()
        // Resolve (and fix the extension of) each item's drive file up front —
        // normalizeName suspends, and the planner's lookup can't.
        val present = feed.associate { item ->
            item.id to driveManager.findPlayableFile(item.path)?.let { normalizeName(it) }?.absolutePath
        }
        val plan = SyncPlanner.plan(
            feed = feed,
            existing = existing,
            presentFile = { present[it.id] }
        )

        store.putAll(plan.upserts)
        if (plan.removedIds.isNotEmpty()) {
            deleteFilesFor(plan.removedIds, existing)
            store.remove(plan.removedIds)
        }

        runCatching { feedApi.getRev(token, store.getDeviceId()).rev }.getOrNull()?.let { store.setRev(it) }

        // Kick the download service if ANYTHING in the store is still pending —
        // not just this sync's upserts. A service killed mid-download leaves
        // records in DOWNLOADING/COMING_SOON/FAILED; without this, they froze
        // until the feed happened to change again.
        val hasQueued = store.all().values.any {
            it.status == DownloadStatus.COMING_SOON ||
                it.status == DownloadStatus.FAILED ||
                it.status == DownloadStatus.DOWNLOADING
        }
        if (hasQueued && StoragePermission.hasAllFilesAccess()) {
            runCatching { DownloadService.start(context) }
        }

        val ready = plan.upserts.count { it.status == DownloadStatus.READY }
        val coming = plan.upserts.count { it.status == DownloadStatus.COMING_SOON }
        SyncOutcome(total = feed.size, ready = ready, comingSoon = coming, removed = plan.removedIds.size)
    }

    private suspend fun fetchAllPages(): List<FeedItemDto> {
        val out = mutableListOf<FeedItemDto>()
        var offset = 0
        while (true) {
            val page = feedApi.getFeed(token, pageSize, offset, store.getDeviceId())
            out += page.items
            offset += page.items.size
            if (page.items.size < pageSize || offset >= page.total) break
        }
        return out
    }

    /**
     * Give a drive file the extension its contents call for (an MKV named `.mp4` →
     * `.mkv`), carrying its hole-scan/hash-check flags across the rename so the
     * library isn't re-verified from scratch.
     */
    private suspend fun normalizeName(file: File): File {
        // Never rename drive files under a playing movie; the next sync catches up.
        if (playbackState.isPlaying.value) return file
        val oldFp = VideoIntegrity.fingerprint(file)
        val renamed = VideoContainer.normalize(file)
        if (renamed != file) {
            store.renameFingerprint(oldFp, VideoIntegrity.fingerprint(renamed))
            android.util.Log.i("MegaflixSync", "renamed ${file.name} -> ${renamed.name} (real container)")
        }
        return renamed
    }

    /** Deletes an item's flat file, but only inside the drive's Megaflix/ folder (safety guard). */
    private fun deleteFilesFor(ids: Collection<String>, existing: Map<String, DownloadRecord>) {
        val mediaDir = driveManager.mediaDir()?.absolutePath ?: return
        ids.forEach { id ->
            val filePath = existing[id]?.localFilePath ?: return@forEach
            val f = File(filePath)
            if (f.absolutePath.startsWith(mediaDir)) f.delete()
        }
    }
}
