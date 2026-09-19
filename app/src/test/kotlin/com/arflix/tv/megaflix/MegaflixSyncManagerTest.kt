package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MegaflixSyncManagerTest {
    private val api = mockk<MegaflixFeedApi>()
    private val store = mockk<DownloadStateStore>(relaxed = true)
    private val drive = mockk<DriveManager>(relaxed = true)

    private fun feedItem(id: String) = FeedItemDto(id = id, tmdbId = 1, title = id, path = "Megaflix/Movies/$id")

    @Test
    fun syncPagesFeedAndWritesRecords() = runTest {
        coEvery { api.getFeed(any(), any(), 0) } returns
            FeedResponseDto(total = 1, offset = 0, limit = 100, items = listOf(feedItem("a")))
        coEvery { store.all() } returns emptyMap()
        coEvery { drive.findPlayableFile(any()) } returns null

        val mgr = MegaflixSyncManager(mockk(relaxed = true), api, store, drive)
        val outcome = mgr.sync().getOrThrow()

        assertThat(outcome.total).isEqualTo(1)
        assertThat(outcome.comingSoon).isEqualTo(1)
        val captured = slot<List<DownloadRecord>>()
        coVerify { store.putAll(capture(captured)) }
        assertThat(captured.captured.single().status).isEqualTo(DownloadStatus.COMING_SOON)
    }

    @Test
    fun revChangedTrueWhenServerDiffers() = runTest {
        coEvery { api.getRev(any()) } returns RevResponseDto("2:t")
        coEvery { store.getRev() } returns "1:t"
        val mgr = MegaflixSyncManager(mockk(relaxed = true), api, store, drive)
        assertThat(mgr.revChanged()).isTrue()
    }

    @Test
    fun revChangedFalseWhenSame() = runTest {
        coEvery { api.getRev(any()) } returns RevResponseDto("1:t")
        coEvery { store.getRev() } returns "1:t"
        val mgr = MegaflixSyncManager(mockk(relaxed = true), api, store, drive)
        assertThat(mgr.revChanged()).isFalse()
    }
}
