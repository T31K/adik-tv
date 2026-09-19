package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus

object DownloadQueue {
    /** Next id to fetch: first COMING_SOON, else first FAILED (retry), else null. */
    fun nextToDownload(records: Map<String, DownloadRecord>): String? {
        records.entries.firstOrNull { it.value.status == DownloadStatus.COMING_SOON }?.let { return it.key }
        records.entries.firstOrNull { it.value.status == DownloadStatus.FAILED }?.let { return it.key }
        return null
    }
}
