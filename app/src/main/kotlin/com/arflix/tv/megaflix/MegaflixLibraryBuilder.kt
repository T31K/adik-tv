package com.arflix.tv.megaflix

import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.model.DownloadStatus
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the Megaflix feed into [MediaItem]s for the "My Library" catalog rows.
 * Metadata (poster/backdrop/overview) comes from TMDB by id; the feed title and
 * description are the fallback when TMDB is unavailable. Download state (localUri +
 * [DownloadStatus]) comes from the [DownloadStateStore] the sync manager writes.
 */
@Singleton
class MegaflixLibraryBuilder @Inject constructor(
    private val syncManager: MegaflixSyncManager,
    private val store: DownloadStateStore,
    private val tmdbApi: TmdbApi
) {
    private val apiKey get() = Constants.TMDB_API_KEY

    suspend fun libraryItems(filter: MediaType?): List<MediaItem> = withContext(Dispatchers.IO) {
        val feed = syncManager.feedItems.value
        val records = store.all()
        val wanted = feed.filter { filter == null || typeOf(it) == filter }

        coroutineScope {
            val gate = Semaphore(6)
            wanted.map { item ->
                async { gate.withPermit { buildItem(item, records[item.id]) } }
            }.awaitAll()
        }
    }

    private fun typeOf(item: FeedItemDto): MediaType =
        if (item.type.equals("series", true) || item.type.equals("tv", true)) MediaType.TV else MediaType.MOVIE

    private suspend fun buildItem(item: FeedItemDto, record: DownloadRecord?): MediaItem {
        val type = typeOf(item)
        val status = record?.status ?: DownloadStatus.COMING_SOON
        val localUri = if (status == DownloadStatus.READY) record?.localFilePath else null

        val base = runCatching {
            if (type == MediaType.TV) {
                val d = tmdbApi.getTvDetails(item.tmdbId, apiKey)
                MediaItem(
                    id = item.tmdbId,
                    title = d.name.takeIf { it.isNotBlank() } ?: item.title,
                    overview = d.overview ?: item.description.orEmpty(),
                    year = d.firstAirDate?.take(4) ?: "",
                    image = d.posterPath?.let { "${Constants.IMAGE_BASE}$it" }
                        ?: d.backdropPath?.let { "${Constants.BACKDROP_BASE}$it" } ?: "",
                    backdrop = d.backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" },
                    originalLanguage = d.originalLanguage,
                    mediaType = MediaType.TV
                )
            } else {
                val d = tmdbApi.getMovieDetails(item.tmdbId, apiKey)
                MediaItem(
                    id = item.tmdbId,
                    title = d.title.takeIf { it.isNotBlank() } ?: item.title,
                    overview = d.overview ?: item.description.orEmpty(),
                    year = d.releaseDate?.take(4) ?: "",
                    image = d.posterPath?.let { "${Constants.IMAGE_BASE}$it" }
                        ?: d.backdropPath?.let { "${Constants.BACKDROP_BASE}$it" } ?: "",
                    backdrop = d.backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" },
                    originalLanguage = d.originalLanguage,
                    mediaType = MediaType.MOVIE
                )
            }
        }.getOrElse {
            // TMDB unavailable → feed-only fallback so the card still appears.
            MediaItem(id = item.tmdbId, title = item.title, overview = item.description.orEmpty(), mediaType = type)
        }

        return base.copy(
            localUri = localUri,
            downloadStatus = status,
            downloadProgress = record?.progress ?: 0f
        )
    }
}
