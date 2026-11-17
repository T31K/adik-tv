package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus

object DownloadQueue {
    /**
     * Next id to fetch: a stale DOWNLOADING record first (service was killed
     * mid-torrent — libtorrent resumes from the pieces already in its temp folder),
     * else first COMING_SOON, else first FAILED (retry), else null.
     * Only one runOnce ever runs at a time (drain is sequential), so a
     * DOWNLOADING record seen here is always an interrupted leftover.
     * Records with a [DownloadRecord.stagedPath] are finished downloads waiting
     * to be moved onto the drive — never re-downloaded.
     */
    fun nextToDownload(records: Map<String, DownloadRecord>): String? {
        val pending = records.entries.filter { it.value.stagedPath == null }
        pending.firstOrNull { it.value.status == DownloadStatus.DOWNLOADING }?.let { return it.key }
        pending.firstOrNull { it.value.status == DownloadStatus.COMING_SOON }?.let { return it.key }
        pending.firstOrNull { it.value.status == DownloadStatus.FAILED }?.let { return it.key }
        return null
    }
}
