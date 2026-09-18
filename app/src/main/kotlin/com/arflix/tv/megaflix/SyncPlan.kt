package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus

data class SyncPlan(
    val upserts: List<DownloadRecord>,
    val removedIds: List<String>
)

object SyncPlanner {
    /**
     * Pure convergence: given the feed, the existing records, and a way to tell
     * whether an item's file is already on the drive, decide the new record for
     * each feed item and which stale ids to drop.
     */
    fun plan(
        feed: List<FeedItemDto>,
        existing: Map<String, DownloadRecord>,
        presentFile: (FeedItemDto) -> String?
    ): SyncPlan {
        val feedIds = feed.map { it.id }.toSet()

        val upserts = feed.map { item ->
            val file = presentFile(item)
            val prev = existing[item.id]
            when {
                // File on the drive → always READY (covers pre-seeded drives and completed downloads).
                file != null -> DownloadRecord(item.id, DownloadStatus.READY, file, 1f, item.sizeBytes)
                // No file, but a download was already underway/failed → preserve that state.
                prev != null && (prev.status == DownloadStatus.DOWNLOADING || prev.status == DownloadStatus.FAILED) ->
                    prev.copy(sizeBytes = item.sizeBytes ?: prev.sizeBytes)
                // Otherwise it's queued.
                else -> DownloadRecord(item.id, DownloadStatus.COMING_SOON, null, 0f, item.sizeBytes)
            }
        }

        val removedIds = existing.keys.filter { it !in feedIds }
        return SyncPlan(upserts, removedIds)
    }
}
