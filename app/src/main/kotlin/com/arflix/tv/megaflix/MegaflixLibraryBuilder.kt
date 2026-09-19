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
 *
 * Flat-file model: each feed row's `path` is a bare filename in the drive's Megaflix/
 * folder. Movies are one row = one card. Series are ONE ROW PER EPISODE sharing a
 * `tmdbId`; they are grouped into a single show card, and each episode's own file is
 * resolved at play time via [cachedEpisodePath] (keyed by tmdbId+season+episode).
 */
@Singleton
class MegaflixLibraryBuilder @Inject constructor(
    private val syncManager: MegaflixSyncManager,
    private val store: DownloadStateStore,
    private val tmdbApi: TmdbApi
) {
    private val apiKey get() = Constants.TMDB_API_KEY

    // (tmdbId, tmdbSeason, tmdbEpisode) -> absolute local file path, READY episodes only.
    // Refreshed on every libraryItems() build; read synchronously at play time.
    @Volatile
    private var episodePathCache: Map<Triple<Int, Int, Int>, String> = emptyMap()

    // tmdbId -> absolute local file path, READY movies only. Used to restore the
    // detail screen's localUri (ARVIO drops it when it re-fetches metadata by id).
    @Volatile
    private var moviePathCache: Map<Int, String> = emptyMap()

    /** Synchronous per-episode file lookup for the player. Null if not downloaded. */
    fun cachedEpisodePath(tmdbId: Int, season: Int, episode: Int): String? =
        episodePathCache[Triple(tmdbId, season, episode)]

    /** Synchronous movie file lookup (restores detail-screen localUri). Null if not downloaded. */
    fun cachedMoviePath(tmdbId: Int): String? = moviePathCache[tmdbId]

    suspend fun libraryItems(filter: MediaType?): List<MediaItem> = withContext(Dispatchers.IO) {
        val feed = syncManager.feedItems.value
        val records = store.all()

        // Refresh the episode-path cache from ALL series rows that are downloaded.
        episodePathCache = feed
            .filter { typeOf(it) == MediaType.TV && it.season != null && it.episode != null }
            .mapNotNull { item ->
                val rec = records[item.id]
                if (rec?.status == DownloadStatus.READY && rec.localFilePath != null)
                    Triple(item.tmdbId, item.season!!, item.episode!!) to rec.localFilePath
                else null
            }.toMap()

        // Refresh the movie-path cache from ALL downloaded movie rows.
        moviePathCache = feed
            .filter { typeOf(it) == MediaType.MOVIE }
            .mapNotNull { item ->
                val rec = records[item.id]
                if (rec?.status == DownloadStatus.READY && rec.localFilePath != null)
                    item.tmdbId to rec.localFilePath else null
            }.toMap()

        val wanted = feed.filter { filter == null || typeOf(it) == filter }

        coroutineScope {
            val gate = Semaphore(6)
            if (filter == MediaType.TV) {
                // Group episode rows into one card per show.
                wanted.groupBy { it.tmdbId }.map { (tmdbId, rows) ->
                    async { gate.withPermit { buildShowItem(tmdbId, rows, records) } }
                }.awaitAll()
            } else if (filter == MediaType.MOVIE) {
                wanted.map { item -> async { gate.withPermit { buildMovieItem(item, records[item.id]) } } }.awaitAll()
            } else {
                // No filter: movies as-is, series grouped by show.
                val movies = wanted.filter { typeOf(it) == MediaType.MOVIE }
                    .map { item -> async { gate.withPermit { buildMovieItem(item, records[item.id]) } } }
                val shows = wanted.filter { typeOf(it) == MediaType.TV }.groupBy { it.tmdbId }
                    .map { (tmdbId, rows) -> async { gate.withPermit { buildShowItem(tmdbId, rows, records) } } }
                (movies + shows).awaitAll()
            }
        }
    }

    private fun typeOf(item: FeedItemDto): MediaType =
        if (item.type.equals("series", true) || item.type.equals("tv", true)) MediaType.TV else MediaType.MOVIE

    private suspend fun buildMovieItem(item: FeedItemDto, record: DownloadRecord?): MediaItem {
        val status = record?.status ?: DownloadStatus.COMING_SOON
        val localUri = if (status == DownloadStatus.READY) record?.localFilePath else null
        val base = runCatching {
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
        }.getOrElse {
            MediaItem(id = item.tmdbId, title = item.title, overview = item.description.orEmpty(), mediaType = MediaType.MOVIE)
        }
        return base.copy(localUri = localUri, downloadStatus = status, downloadProgress = record?.progress ?: 0f)
    }

    private suspend fun buildShowItem(tmdbId: Int, rows: List<FeedItemDto>, records: Map<String, DownloadRecord>): MediaItem {
        val statuses = rows.map { records[it.id]?.status ?: DownloadStatus.COMING_SOON }
        // Show is enterable/READY as soon as any episode is on the drive.
        val showStatus = when {
            statuses.any { it == DownloadStatus.READY } -> DownloadStatus.READY
            statuses.any { it == DownloadStatus.DOWNLOADING } -> DownloadStatus.DOWNLOADING
            statuses.any { it == DownloadStatus.FAILED } -> DownloadStatus.FAILED
            else -> DownloadStatus.COMING_SOON
        }
        val fallbackTitle = rows.firstOrNull()?.title ?: "Unknown"
        val base = runCatching {
            val d = tmdbApi.getTvDetails(tmdbId, apiKey)
            MediaItem(
                id = tmdbId,
                title = d.name.takeIf { it.isNotBlank() } ?: fallbackTitle,
                overview = d.overview ?: rows.firstOrNull()?.description.orEmpty(),
                year = d.firstAirDate?.take(4) ?: "",
                image = d.posterPath?.let { "${Constants.IMAGE_BASE}$it" }
                    ?: d.backdropPath?.let { "${Constants.BACKDROP_BASE}$it" } ?: "",
                backdrop = d.backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" },
                originalLanguage = d.originalLanguage,
                mediaType = MediaType.TV
            )
        }.getOrElse {
            MediaItem(id = tmdbId, title = fallbackTitle, overview = rows.firstOrNull()?.description.orEmpty(), mediaType = MediaType.TV)
        }
        // A show has no single localUri — episode files resolve via cachedEpisodePath at play time.
        return base.copy(localUri = null, downloadStatus = showStatus, downloadProgress = 0f)
    }
}
