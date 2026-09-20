package com.arflix.tv.data.model

/** Megaflix per-item download state, surfaced on cards and the detail page. */
enum class DownloadStatus {
    READY,        // file present on the drive; playable
    DOWNLOADING,  // torrent in progress (Milestone D)
    COMING_SOON,  // in the feed, not yet on the drive
    FAILED,       // download failed; will be retried (Milestone D)
    PAUSED        // user paused/stopped it in Settings → Downloads; auto-sync leaves it be until Start
}
