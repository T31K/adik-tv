package com.arflix.tv.megaflix

import androidx.annotation.Keep
import com.arflix.tv.data.model.DownloadStatus

@Keep
data class DownloadRecord(
    val id: String,
    val status: DownloadStatus,
    val localFilePath: String? = null, // absolute path to the playable video file when READY
    val progress: Float = 0f,
    val sizeBytes: Long? = null,
    // Which of the feed item's ranked magnets (links[]) this download is using.
    // The stall poller advances it to fall back to a better-seeded alternate.
    val linkIndex: Int = 0
)
