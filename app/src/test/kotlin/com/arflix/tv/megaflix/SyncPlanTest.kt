package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncPlanTest {
    private fun item(id: String) = FeedItemDto(id = id, tmdbId = 1, title = id, path = "P/$id")

    @Test
    fun newItemWithFileOnDriveBecomesReady() {
        val plan = SyncPlanner.plan(
            feed = listOf(item("a")),
            existing = emptyMap(),
            presentFile = { "/drive/P/a/a.mkv" }
        )
        assertThat(plan.upserts).hasSize(1)
        assertThat(plan.upserts[0].status).isEqualTo(DownloadStatus.READY)
        assertThat(plan.upserts[0].localFilePath).isEqualTo("/drive/P/a/a.mkv")
        assertThat(plan.removedIds).isEmpty()
    }

    @Test
    fun newItemWithoutFileBecomesComingSoon() {
        val plan = SyncPlanner.plan(listOf(item("a")), emptyMap(), presentFile = { null })
        assertThat(plan.upserts[0].status).isEqualTo(DownloadStatus.COMING_SOON)
        assertThat(plan.upserts[0].localFilePath).isNull()
    }

    @Test
    fun inProgressItemIsPreservedWhenStillNoFile() {
        val existing = mapOf("a" to DownloadRecord("a", DownloadStatus.DOWNLOADING, null, 0.5f, null))
        val plan = SyncPlanner.plan(listOf(item("a")), existing, presentFile = { null })
        assertThat(plan.upserts[0].status).isEqualTo(DownloadStatus.DOWNLOADING)
        assertThat(plan.upserts[0].progress).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun downloadingItemFlipsToReadyOnceFileAppears() {
        val existing = mapOf("a" to DownloadRecord("a", DownloadStatus.DOWNLOADING, null, 0.9f, null))
        val plan = SyncPlanner.plan(listOf(item("a")), existing, presentFile = { "/drive/P/a/a.mkv" })
        assertThat(plan.upserts[0].status).isEqualTo(DownloadStatus.READY)
        assertThat(plan.upserts[0].progress).isEqualTo(1f)
    }

    @Test
    fun vanishedItemsAreRemoved() {
        val existing = mapOf(
            "a" to DownloadRecord("a", DownloadStatus.READY, "/drive/P/a/a.mkv", 1f, null),
            "gone" to DownloadRecord("gone", DownloadStatus.READY, "/drive/P/gone/g.mkv", 1f, null)
        )
        val plan = SyncPlanner.plan(listOf(item("a")), existing, presentFile = { "/drive/P/a/a.mkv" })
        assertThat(plan.removedIds).containsExactly("gone")
    }
}
