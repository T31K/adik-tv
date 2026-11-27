package com.arflix.tv.megaflix

import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.api.TmdbMovieDetails
import com.arflix.tv.data.model.DownloadStatus
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.stream.StreamCatalog
import com.arflix.tv.stream.StreamMovieDto
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
    private val streamMovies = MutableStateFlow<List<StreamMovieDto>>(emptyList())
    private val stream = mockk<StreamCatalog>().also { every { it.movies } returns streamMovies }

    private fun MegaflixLibraryBuilder(sync: MegaflixSyncManager, store: DownloadStateStore, tmdb: TmdbApi) =
        com.arflix.tv.megaflix.MegaflixLibraryBuilder(sync, store, tmdb, stream)

    private val demon = StreamMovieDto(id = "demon_slayer_infinity_castle_2025", tmdbId = 1311031,
        title = "Demon Slayer: Infinity Castle", plaintextSize = 2_400_000_000)

    @Test
    fun streamMovieIsANormalReadyEntryWithAStreamUri() = runTest {
        val feed = listOf(FeedItemDto(id = "a", type = "movie", tmdbId = 10378, title = "Big Buck Bunny", link = "magnet:?x", path = "bbb.mkv"))
        every { sync.feedItems } returns MutableStateFlow(feed)
        coEvery { store.all() } returns mapOf("a" to DownloadRecord("a", DownloadStatus.READY, "/drive/bbb.mkv", 1f, null))
        coEvery { tmdb.getMovieDetails(10378, any(), any(), any()) } returns TmdbMovieDetails(id = 10378, title = "Big Buck Bunny")
        coEvery { tmdb.getMovieDetails(1311031, any(), any(), any()) } returns
            TmdbMovieDetails(id = 1311031, title = "Demon Slayer: Kimetsu no Yaiba Infinity Castle")
        streamMovies.value = listOf(demon)

        val items = MegaflixLibraryBuilder(sync, store, tmdb).libraryItems(MediaType.MOVIE)

        assertThat(items.map { it.id }).containsExactly(10378, 1311031)
        val ds = items.single { it.id == 1311031 }
        assertThat(ds.title).isEqualTo("Demon Slayer: Kimetsu no Yaiba Infinity Castle")
        assertThat(ds.localUri).isEqualTo("adikstream://demon_slayer_infinity_castle_2025")
        assertThat(ds.downloadStatus).isEqualTo(DownloadStatus.READY)
    }

    @Test
    fun streamMovieResolvesThroughCachedMoviePath() = runTest {
        every { sync.feedItems } returns MutableStateFlow(emptyList())
        coEvery { store.all() } returns emptyMap()
        coEvery { tmdb.getMovieDetails(any(), any(), any(), any()) } throws RuntimeException("offline")
        streamMovies.value = listOf(demon)

        val builder = MegaflixLibraryBuilder(sync, store, tmdb)
        val items = builder.libraryItems(null)

        assertThat(items.single().title).isEqualTo("Demon Slayer: Infinity Castle") // TMDB down → stream title
        assertThat(builder.cachedMoviePath(1311031)).isEqualTo("adikstream://demon_slayer_infinity_castle_2025")
    }

    @Test
    fun movieCacheRebuildsWhenTheStreamListChanges() = runTest {
        every { sync.feedItems } returns MutableStateFlow(emptyList())
        coEvery { store.all() } returns emptyMap()
        coEvery { tmdb.getMovieDetails(any(), any(), any(), any()) } throws RuntimeException("offline")
        val builder = MegaflixLibraryBuilder(sync, store, tmdb)

        assertThat(builder.libraryItems(MediaType.MOVIE)).isEmpty()
        streamMovies.value = listOf(demon)
        assertThat(builder.libraryItems(MediaType.MOVIE)).hasSize(1)
    }

    @Test
    fun streamMovieAlreadyOnTheDriveIsNotListedTwice() = runTest {
        val feed = listOf(FeedItemDto(id = "ds", type = "movie", tmdbId = 1311031, title = "DS", link = "magnet:?x", path = "ds.mkv"))
        every { sync.feedItems } returns MutableStateFlow(feed)
        coEvery { store.all() } returns mapOf("ds" to DownloadRecord("ds", DownloadStatus.READY, "/drive/ds.mkv", 1f, null))
        coEvery { tmdb.getMovieDetails(any(), any(), any(), any()) } throws RuntimeException("offline")
        streamMovies.value = listOf(demon)

        val items = MegaflixLibraryBuilder(sync, store, tmdb).libraryItems(MediaType.MOVIE)

        assertThat(items).hasSize(1)
        assertThat(items.single().localUri).isEqualTo("file:///drive/ds.mkv") // the drive copy wins
    }

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
        // Local files are handed to the player as file:// URIs.
        assertThat(items[0].localUri).isEqualTo("file:///drive/Megaflix/Movies/BBB/bbb.mkv")
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
