package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadQueueTest {
    private fun rec(id: String, s: DownloadStatus) = DownloadRecord(id, s, null, 0f, null)

    @Test fun picksFirstComingSoon() {
        val recs = linkedMapOf(
            "a" to rec("a", DownloadStatus.READY),
            "b" to rec("b", DownloadStatus.COMING_SOON),
            "c" to rec("c", DownloadStatus.COMING_SOON))
        assertThat(DownloadQueue.nextToDownload(recs)).isEqualTo("b")
    }

    @Test fun picksFailedWhenNoComingSoon() {
        val recs = linkedMapOf(
            "a" to rec("a", DownloadStatus.READY),
            "b" to rec("b", DownloadStatus.FAILED))
        assertThat(DownloadQueue.nextToDownload(recs)).isEqualTo("b")
    }

    @Test fun skipsStagedDownloadsWaitingForDriveMove() {
        val recs = linkedMapOf(
            "a" to rec("a", DownloadStatus.DOWNLOADING).copy(stagedPath = "/data/dl/a/a.mkv", progress = 1f),
            "b" to rec("b", DownloadStatus.COMING_SOON))
        assertThat(DownloadQueue.nextToDownload(recs)).isEqualTo("b")
        assertThat(DownloadQueue.nextToDownload(mapOf("a" to recs.getValue("a")))).isNull()
    }

    @Test fun resumesInterruptedDownloadFirst() {
        // A DOWNLOADING record seen by the (sequential) drain is a killed leftover.
        val recs = linkedMapOf(
            "a" to rec("a", DownloadStatus.COMING_SOON),
            "b" to rec("b", DownloadStatus.DOWNLOADING))
        assertThat(DownloadQueue.nextToDownload(recs)).isEqualTo("b")
    }

    @Test fun nullWhenAllReady() {
        val recs = mapOf("a" to rec("a", DownloadStatus.READY))
        assertThat(DownloadQueue.nextToDownload(recs)).isNull()
    }
}
