package com.arflix.tv.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaItemDownloadTest {
    @Test
    fun defaultsToReadyWithNoProgress() {
        val item = MediaItem(id = 1, title = "X")
        assertThat(item.downloadStatus).isEqualTo(DownloadStatus.READY)
        assertThat(item.downloadProgress).isEqualTo(0f)
    }

    @Test
    fun canMarkComingSoon() {
        val item = MediaItem(id = 1, title = "X")
            .copy(downloadStatus = DownloadStatus.DOWNLOADING, downloadProgress = 0.42f)
        assertThat(item.downloadStatus).isEqualTo(DownloadStatus.DOWNLOADING)
        assertThat(item.downloadProgress).isWithin(0.001f).of(0.42f)
    }
}
