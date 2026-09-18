package com.arflix.tv.megaflix

import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.api.TmdbMovieDetails
import com.arflix.tv.data.model.DownloadStatus
import com.arflix.tv.data.model.MediaType
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MegaflixLibraryBuilderTest {
    private val sync = mockk<MegaflixSyncManager>()
    private val store = mockk<DownloadStateStore>()
    private val tmdb = mockk<TmdbApi>()

    @Test
    fun readyItemGetsLocalUriAndReadyStatus() = runTest {
        val feed = listOf(FeedItemDto(id = "a", type = "movie", tmdbId = 10378, title = "Big Buck Bunny",
            description = "d", link = "magnet:?x", path = "Megaflix/Movies/BBB"))
        every { sync.feedItems } returns MutableStateFlow(feed)
        coEvery { store.all() } returns mapOf(
            "a" to DownloadRecord("a", DownloadStatus.READY, "/drive/Megaflix/Movies/BBB/bbb.mkv", 1f, null))
        coEvery { tmdb.getMovieDetails(10378, any(), any(), any()) } returns
            TmdbMovieDetails(id = 10378, title = "Big Buck Bunny")

        val builder = MegaflixLibraryBuilder(sync, store, tmdb)
        val items = builder.libraryItems(MediaType.MOVIE)

        assertThat(items).hasSize(1)
        assertThat(items[0].id).isEqualTo(10378)
        assertThat(items[0].downloadStatus).isEqualTo(DownloadStatus.READY)
        assertThat(items[0].localUri).isEqualTo("/drive/Megaflix/Movies/BBB/bbb.mkv")
    }

    @Test
    fun comingSoonItemHasNoLocalUriAndUsesFeedFallbackTitle() = runTest {
        val feed = listOf(FeedItemDto(id = "b", type = "movie", tmdbId = 999, title = "Feed Title",
            description = "feed desc", link = "magnet:?x", path = "Megaflix/Movies/B"))
        every { sync.feedItems } returns MutableStateFlow(feed)
        coEvery { store.all() } returns mapOf("b" to DownloadRecord("b", DownloadStatus.COMING_SOON, null, 0f, null))
        coEvery { tmdb.getMovieDetails(999, any(), any(), any()) } throws RuntimeException("tmdb down")

        val builder = MegaflixLibraryBuilder(sync, store, tmdb)
        val items = builder.libraryItems(MediaType.MOVIE)

        assertThat(items).hasSize(1)
        assertThat(items[0].downloadStatus).isEqualTo(DownloadStatus.COMING_SOON)
        assertThat(items[0].localUri).isNull()
        assertThat(items[0].title).isEqualTo("Feed Title") // TMDB failed → feed fallback
    }

    @Test
    fun filtersByType() = runTest {
        val feed = listOf(
            FeedItemDto(id = "m", type = "movie", tmdbId = 1, title = "M", link = "x", path = "Megaflix/Movies/M"),
            FeedItemDto(id = "s", type = "series", tmdbId = 2, title = "S", link = "x", path = "Megaflix/TV/S")
        )
        every { sync.feedItems } returns MutableStateFlow(feed)
        coEvery { store.all() } returns mapOf(
            "m" to DownloadRecord("m", DownloadStatus.COMING_SOON, null, 0f, null),
            "s" to DownloadRecord("s", DownloadStatus.COMING_SOON, null, 0f, null))
        coEvery { tmdb.getMovieDetails(any(), any(), any(), any()) } throws RuntimeException("x")

        val builder = MegaflixLibraryBuilder(sync, store, tmdb)
        assertThat(builder.libraryItems(MediaType.MOVIE).map { it.title }).containsExactly("M")
    }
}
