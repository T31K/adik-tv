package com.arflix.tv.data.repository

import android.content.Context
import com.arflix.tv.R
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.api.TmdbCastMember
import com.arflix.tv.data.api.TmdbCrewMember
import com.arflix.tv.data.api.TmdbEpisode
import com.arflix.tv.data.api.TmdbExternalIds
import com.arflix.tv.data.api.TmdbImage
import com.arflix.tv.data.api.TmdbMediaItem
import com.arflix.tv.data.api.TmdbMovieDetails
import com.arflix.tv.data.api.TmdbPersonDetails
import com.arflix.tv.data.api.TmdbTvDetails
import com.arflix.tv.data.api.TmdbWatchProviderRegion
import com.arflix.tv.data.model.CastMember
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.Episode
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.PersonDetails
import com.arflix.tv.data.model.Review
import com.arflix.tv.megaflix.MegaflixLibraryBuilder
import com.arflix.tv.util.Constants
import com.arflix.tv.util.ContentRating
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class StreamingServiceInfo(
    val id: Int,
    val name: String,
    val logoUrl: String? = null
)

data class StreamingServicesResult(
    val region: String,
    val services: List<StreamingServiceInfo>
)

data class PersonMediaSearchResult(
    val personId: Int,
    val name: String,
    val items: List<MediaItem>
)

data class MediaSearchResults(
    val items: List<MediaItem>,
    val people: List<PersonMediaSearchResult>
)

/**
 * TMDB metadata (details, seasons, cast, similar, logos, trailers, people, search, discover,
 * reviews) with in-memory caching, plus the feed-driven local library rows.
 */
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbApi: TmdbApi,
    private val megaflixLibrary: MegaflixLibraryBuilder,
    private val playbackProgressStore: PlaybackProgressStore
) {

    private val apiKey = Constants.TMDB_API_KEY
    private val gson = Gson()

    /** TMDB content language (e.g. "en-US", "fr-FR", "nl-NL"). */
    @Volatile
    var contentLanguage: String = "en-US"
        set(value) {
            val normalized = value.ifBlank { "en-US" }.replace("iw", "he").replace('_', '-')
            if (field == normalized) return
            field = normalized
            clearMediaCache()
        }

    // === IN-MEMORY CACHE ===
    private data class CacheEntry<T>(val data: T, val timestamp: Long)
    private val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    private val detailsCache = ConcurrentHashMap<String, CacheEntry<MediaItem>>()
    private val fullDetailsCacheKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val castCache = ConcurrentHashMap<String, CacheEntry<List<CastMember>>>()
    private val similarCache = ConcurrentHashMap<String, CacheEntry<List<MediaItem>>>()
    private val logoCache = ConcurrentHashMap<String, CacheEntry<String?>>()
    private val reviewsCache = ConcurrentHashMap<String, CacheEntry<List<Review>>>()
    private val watchProvidersCache = ConcurrentHashMap<String, CacheEntry<StreamingServicesResult?>>()
    private val seasonEpisodesCache = ConcurrentHashMap<String, CacheEntry<List<Episode>>>()
    private val imdbIdCache = ConcurrentHashMap<String, String>()

    fun clearMediaCache() {
        detailsCache.clear()
        fullDetailsCacheKeys.clear()
        castCache.clear()
        similarCache.clear()
        logoCache.clear()
        reviewsCache.clear()
        watchProvidersCache.clear()
        seasonEpisodesCache.clear()
    }

    private fun <T> getFromCache(cache: Map<String, CacheEntry<T>>, key: String): T? {
        val entry = cache[key] ?: return null
        return if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) entry.data else null
    }

    private fun detailsCacheKey(mediaType: MediaType, mediaId: Int): String {
        return if (mediaType == MediaType.MOVIE) "movie_$mediaId" else "tv_$mediaId"
    }

    fun getCachedItem(mediaType: MediaType, mediaId: Int): MediaItem? =
        getFromCache(detailsCache, detailsCacheKey(mediaType, mediaId))

    suspend fun getCachedItemFromDisk(mediaType: MediaType, mediaId: Int): MediaItem? =
        withContext(Dispatchers.IO) { peekItemFromDiskCache(mediaType, mediaId) }

    /** Looks the item up in the persisted home rows cache written by HomeViewModel. */
    private fun peekItemFromDiskCache(mediaType: MediaType, mediaId: Int): MediaItem? = try {
        val categoryListType = com.google.gson.reflect.TypeToken
            .getParameterized(MutableList::class.java, Category::class.java)
            .type
        val files = context.cacheDir
            .listFiles { _, name -> name.startsWith("home_categories_cache_") && name.endsWith(".json") }
            .orEmpty()
        var found: MediaItem? = null
        for (file in files) {
            if (!file.exists() || file.length() > 12_000_000L) continue
            val json = file.readText()
            if (json.isBlank()) continue
            if (!json.contains("\"id\":$mediaId") && !json.contains("\"id\": $mediaId")) continue
            val categories: List<Category>? =
                runCatching { gson.fromJson<List<Category>>(json, categoryListType) }.getOrNull()
            found = categories
                ?.asSequence()
                ?.flatMap { it.items.asSequence() }
                ?.firstOrNull { it.id == mediaId && it.mediaType == mediaType }
            if (found != null) break
        }
        found?.also { cacheItem(it) }
    } catch (_: Throwable) {
        null
    }

    fun getCachedFullItem(mediaType: MediaType, mediaId: Int): MediaItem? {
        val cacheKey = detailsCacheKey(mediaType, mediaId)
        if (cacheKey !in fullDetailsCacheKeys) return null
        val cached = getFromCache(detailsCache, cacheKey)
        if (cached == null) fullDetailsCacheKeys.remove(cacheKey)
        return cached
    }

    fun cacheImdbId(mediaType: MediaType, mediaId: Int, imdbId: String) {
        if (imdbId.isBlank()) return
        imdbIdCache[detailsCacheKey(mediaType, mediaId)] = imdbId
    }

    fun getCachedImdbId(mediaType: MediaType, mediaId: Int): String? =
        imdbIdCache[detailsCacheKey(mediaType, mediaId)]

    private suspend fun resolveExternalIds(mediaType: MediaType, mediaId: Int): TmdbExternalIds? {
        return try {
            when (mediaType) {
                MediaType.MOVIE -> tmdbApi.getMovieExternalIds(mediaId, apiKey)
                MediaType.TV -> tmdbApi.getTvExternalIds(mediaId, apiKey)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            com.arflix.tv.util.AppLogger.recordException(e, mapOf("error_area" to "MediaRepository", "action" to "resolveExternalIds"))
            null
        }
    }

    fun cacheItem(item: MediaItem) {
        val cacheKey = detailsCacheKey(item.mediaType, item.id)
        if (cacheKey in fullDetailsCacheKeys) {
            if (getFromCache(detailsCache, cacheKey) != null) return
            fullDetailsCacheKeys.remove(cacheKey)
        }
        detailsCache[cacheKey] = CacheEntry(item, System.currentTimeMillis())
    }

    private fun cacheFullDetailsItem(item: MediaItem) {
        val cacheKey = detailsCacheKey(item.mediaType, item.id)
        detailsCache[cacheKey] = CacheEntry(item, System.currentTimeMillis())
        fullDetailsCacheKeys.add(cacheKey)
    }

    private fun cacheItems(items: List<MediaItem>) {
        items.forEach { cacheItem(it) }
    }

    // ---- Local (feed-driven) library ------------------------------------------

    fun localUriForId(tmdbId: Int): String? = megaflixLibrary.cachedMoviePath(tmdbId)

    /**
     * A home row from the feed. Movies are built once (cached by MegaflixLibraryBuilder) and
     * each row filters that list: `cat_new` (recent), `cat_all`, `cat_g_<genreId>`,
     * `cat_co_<companyId-companyId…>`; `local_tv` lists series.
     */
    suspend fun loadLocalCatalog(catalog: CatalogConfig, maxItems: Int = 200): Category? {
        if (catalog.id == "local_tv") {
            val tv = megaflixLibrary.libraryItems(MediaType.TV).take(maxItems)
            return if (tv.isEmpty()) null else Category(catalog.id, catalog.title, tv)
        }
        val all = megaflixLibrary.libraryItems(MediaType.MOVIE)
        val items = when {
            catalog.id == "cat_new" -> all.sortedByDescending { it.year }.take(maxItems)
            catalog.id == "cat_all" -> all.sortedBy { it.title }.take(maxItems)
            catalog.id.startsWith("cat_g_") -> {
                val g = catalog.id.removePrefix("cat_g_").toIntOrNull()
                all.filter { g != null && it.genreIds.contains(g) }.take(maxItems)
            }
            catalog.id.startsWith("cat_co_") -> {
                val ids = catalog.id.removePrefix("cat_co_").split("-").mapNotNull { it.toIntOrNull() }.toSet()
                all.filter { item -> item.companyIds.any { it in ids } }.take(maxItems)
            }
            else -> all.take(maxItems)
        }
        return if (items.isEmpty()) null else Category(catalog.id, catalog.title, items)
    }

    /** Per-episode local file for a series (flat model). Null if not downloaded. */
    fun megaflixEpisodePath(tmdbId: Int, season: Int, episode: Int): String? =
        megaflixLibrary.cachedEpisodePath(tmdbId, season, episode)


    // ---- TMDB metadata --------------------------------------------------------

    suspend fun getMovieDetails(movieId: Int): MediaItem {
        val cacheKey = detailsCacheKey(MediaType.MOVIE, movieId)
        if (cacheKey in fullDetailsCacheKeys) getFromCache(detailsCache, cacheKey)?.let { return it }

        val item = coroutineScope {
            val detailsDeferred = async { tmdbApi.getMovieDetails(movieId, apiKey, language = contentLanguage) }
            val externalIdsDeferred = async { resolveExternalIds(MediaType.MOVIE, movieId) }
            val details = detailsDeferred.await()
            externalIdsDeferred.await()?.imdbId?.let { cacheImdbId(MediaType.MOVIE, movieId, it) }
            details.toMediaItem().copy(
                contentRating = ContentRating.forMovie(details.releaseDates, contentLanguage)
            )
        }
        cacheFullDetailsItem(item)
        return item
    }

    suspend fun getTvDetails(tvId: Int): MediaItem {
        val cacheKey = detailsCacheKey(MediaType.TV, tvId)
        if (cacheKey in fullDetailsCacheKeys) getFromCache(detailsCache, cacheKey)?.let { return it }

        val item = coroutineScope {
            val detailsDeferred = async { tmdbApi.getTvDetails(tvId, apiKey, language = contentLanguage) }
            val externalIdsDeferred = async { resolveExternalIds(MediaType.TV, tvId) }
            val details = detailsDeferred.await()
            externalIdsDeferred.await()?.imdbId?.let { cacheImdbId(MediaType.TV, tvId, it) }
            details.toMediaItem().copy(
                contentRating = ContentRating.forTv(details.contentRatings, contentLanguage)
            )
        }
        cacheFullDetailsItem(item)
        return item
    }

    /** The TMDB collection (franchise) a movie belongs to, from /movie/{id}. */
    suspend fun getMovieCollectionRef(movieId: Int): com.arflix.tv.data.api.TmdbCollectionRef? {
        return try {
            tmdbApi.getMovieDetails(movieId, apiKey, language = contentLanguage).belongsToCollection
        } catch (e: retrofit2.HttpException) {
            null
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** All movies in a TMDB collection, oldest first (Details franchise row). */
    suspend fun getTmdbCollectionItems(collectionId: Int): List<MediaItem> {
        val response = try {
            tmdbApi.getTmdbCollection(collectionId, apiKey, language = contentLanguage)
        } catch (e: retrofit2.HttpException) {
            null
        } catch (e: java.io.IOException) {
            null
        } ?: return emptyList()
        return response.parts
            .sortedBy { it.releaseDate.orEmpty() }
            .map { it.toMediaItem(MediaType.MOVIE) }
    }

    /** Season episodes from TMDB, with watched flags from local playback progress. */
    suspend fun getSeasonEpisodes(tvId: Int, seasonNumber: Int): List<Episode> {
        val cacheKey = "tv_${tvId}_season_$seasonNumber"
        val episodes = getFromCache(seasonEpisodesCache, cacheKey) ?: run {
            val season = tmdbApi.getTvSeason(tvId, seasonNumber, apiKey, language = contentLanguage)
            season.episodes.map { it.toEpisode() }.also {
                seasonEpisodesCache[cacheKey] = CacheEntry(it, System.currentTimeMillis())
            }
        }
        val watched = runCatching {
            playbackProgressStore.episodesFor(tvId)
                .filter { it.watched && it.season != null && it.episode != null }
                .map { it.season!! to it.episode!! }
                .toSet()
        }.getOrDefault(emptySet())
        if (watched.isEmpty()) return episodes
        return episodes.map { ep ->
            if ((ep.seasonNumber to ep.episodeNumber) in watched) ep.copy(isWatched = true) else ep
        }
    }

    suspend fun getCast(mediaType: MediaType, mediaId: Int): List<CastMember> {
        val cacheKey = "${mediaType}_cast_$mediaId"
        getFromCache(castCache, cacheKey)?.let { return it }

        val type = if (mediaType == MediaType.TV) "tv" else "movie"
        val credits = tmdbApi.getCredits(type, mediaId, apiKey, language = contentLanguage)
        // The director goes first, ahead of the cast.
        val director = credits.crew.firstOrNull { it.job == "Director" }
        val castMembers = credits.cast
            .distinctBy { it.id } // TMDB can occasionally return duplicate cast IDs.
            .take(15)
            .map { it.toCastMember() }
        val result = if (director != null) listOf(director.toDirectorCastMember()) + castMembers else castMembers
        castCache[cacheKey] = CacheEntry(result, System.currentTimeMillis())
        return result
    }

    /**
     * "More Like This", drawn only from this TV's library: first the library titles
     * TMDB recommends for this one (in TMDB's order), then library titles sharing the
     * most genres. Downloaded titles come before ones still coming.
     */
    suspend fun getSimilar(mediaType: MediaType, mediaId: Int): List<MediaItem> {
        val cacheKey = "${mediaType}_similar_$mediaId"
        getFromCache(similarCache, cacheKey)?.let { return it }

        val library = megaflixLibrary.libraryItems(mediaType).filter { it.id != mediaId }
        if (library.isEmpty()) return emptyList()

        val type = if (mediaType == MediaType.TV) "tv" else "movie"
        suspend fun tmdbIds(fetch: suspend () -> List<Int>): List<Int> = try {
            fetch()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
        // Up to 2 pages of each: more TMDB picks means more overlap with a small library.
        val recommended = (1..2).flatMap { page ->
            tmdbIds { tmdbApi.getRecommendations(type, mediaId, apiKey, language = contentLanguage, page = page).results.map { it.id } }
        } + (1..2).flatMap { page ->
            tmdbIds { tmdbApi.getSimilar(type, mediaId, apiKey, language = contentLanguage, page = page).results.map { it.id } }
        }
        val rank = recommended.distinct().withIndex().associate { (i, id) -> id to i }

        val genres = (library.firstOrNull { it.id == mediaId }?.genreIds
            ?: runCatching { getMovieGenres(type, mediaId) }.getOrDefault(emptyList())).toSet()

        val ready = { item: MediaItem -> item.downloadStatus == com.arflix.tv.data.model.DownloadStatus.READY }
        val result = library
            .map { item -> item to (rank[item.id] to item.genreIds.count { it in genres }) }
            .filter { (_, s) -> s.first != null || s.second > 0 }
            .sortedWith(
                compareBy<Pair<MediaItem, Pair<Int?, Int>>> { (_, s) -> if (s.first != null) 0 else 1 }
                    .thenBy { (_, s) -> s.first ?: Int.MAX_VALUE }
                    .thenByDescending { (_, s) -> s.second }
                    .thenBy { (item, _) -> if (ready(item)) 0 else 1 }
            )
            .map { it.first }
            .sortedBy { if (ready(it)) 0 else 1 } // stable: keeps relevance order within each group
            .take(12)
        similarCache[cacheKey] = CacheEntry(result, System.currentTimeMillis())
        return result
    }

    private suspend fun getMovieGenres(type: String, mediaId: Int): List<Int> =
        if (type == "movie") tmdbApi.getMovieDetails(mediaId, apiKey).genres.map { it.id } else emptyList()

    suspend fun getLogoUrl(mediaType: MediaType, mediaId: Int): String? {
        val cacheKey = "${mediaType}_logo_$mediaId"
        logoCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) return cached.data
            logoCache.remove(cacheKey)
        }

        val type = if (mediaType == MediaType.TV) "tv" else "movie"
        return try {
            val images = tmdbApi.getImages(type, mediaId, apiKey)
            // Prefer PNG over SVG (no SVG decoder), English, then the best-rated / widest logo.
            val logo = images.logos
                .asSequence()
                .filter { !it.filePath.isNullOrBlank() }
                .filterNot { it.filePath!!.endsWith(".svg", ignoreCase = true) }
                .sortedWith(
                    compareByDescending<TmdbImage> { it.iso6391 == "en" }
                        .thenByDescending { it.voteAverage }
                        .thenByDescending { it.width }
                )
                .firstOrNull()
                ?: images.logos.firstOrNull()
            val url = logo?.filePath?.let { "${Constants.LOGO_BASE}$it" }
            logoCache[cacheKey] = CacheEntry(url, System.currentTimeMillis())
            url
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    suspend fun getLogoUrl(item: MediaItem): String? =
        item.id.takeIf { it > 0 }?.let { getLogoUrl(item.mediaType, it) }

    /** Instant synchronous peek into the in-memory or persisted logo cache. */
    fun peekCachedLogoUrl(mediaType: MediaType, mediaId: Int): String? {
        val keys = listOf(
            "${mediaType}_logo_$mediaId",
            "${mediaType}_$mediaId",
            "${mediaType.name.lowercase()}_logo_$mediaId",
            "${mediaType.name.lowercase()}_$mediaId",
            "${mediaType.name.uppercase()}_logo_$mediaId",
            "${mediaType.name.uppercase()}_$mediaId"
        )
        for (k in keys) {
            val cached = getFromCache(logoCache, k)
            if (!cached.isNullOrBlank()) return cached
        }
        try {
            val json = context.getSharedPreferences("logo_cache", Context.MODE_PRIVATE).getString("urls", null)
            if (!json.isNullOrBlank()) {
                val jsonObject = org.json.JSONObject(json)
                for (k in keys) {
                    val url = jsonObject.optString(k)
                    if (url.isNotBlank()) {
                        logoCache["${mediaType}_logo_$mediaId"] = CacheEntry(url, System.currentTimeMillis())
                        return url
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    fun cacheLogoUrl(mediaType: MediaType, mediaId: Int, logoUrl: String) {
        if (logoUrl.isBlank()) return
        logoCache["${mediaType}_logo_$mediaId"] = CacheEntry(logoUrl, System.currentTimeMillis())
    }

    /** Instant synchronous peek into the in-memory season episodes cache. */
    fun peekCachedSeasonEpisodes(tvId: Int, seasonNumber: Int): List<Episode>? =
        getFromCache(seasonEpisodesCache, "tv_${tvId}_season_$seasonNumber")

    /** YouTube trailer key. */
    suspend fun getTrailerKey(mediaType: MediaType, mediaId: Int): String? {
        val type = if (mediaType == MediaType.TV) "tv" else "movie"
        return try {
            var results = tmdbApi.getVideos(type, mediaId, apiKey, language = contentLanguage).results
            // Most titles only have English-tagged trailers; retry explicitly with "en-US"
            // (a null language would be refilled with the user's language by the interceptor).
            if (results.none { it.site == "YouTube" } && !contentLanguage.equals("en-US", ignoreCase = true)) {
                results = tmdbApi.getVideos(type, mediaId, apiKey, language = "en-US").results
            }
            val trailer = results.find { it.type == "Trailer" && it.site == "YouTube" && it.official }
                ?: results.find { it.type == "Trailer" && it.site == "YouTube" }
                ?: results.find { it.type == "Teaser" && it.site == "YouTube" }
                ?: results.find { it.site == "YouTube" }
            trailer?.key
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    suspend fun getPersonDetails(personId: Int): PersonDetails =
        tmdbApi.getPersonDetails(personId, apiKey, language = contentLanguage).toPersonDetails()

    suspend fun search(query: String): List<MediaItem> {
        val results = tmdbApi.searchMulti(apiKey, query, language = contentLanguage)
        val items = results.results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .map { it.toMediaItem(if (it.mediaType == "tv") MediaType.TV else MediaType.MOVIE) }
        cacheItems(items)
        return items
    }

    /** Titles and known-for rows share one request; optional artwork must not delay them. */
    suspend fun searchWithPeople(query: String, maxPeople: Int = 3): MediaSearchResults {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return MediaSearchResults(emptyList(), emptyList())

        val response = tmdbApi.searchMulti(apiKey, trimmed, language = contentLanguage)
        val items = response.results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .map { it.toMediaItem(if (it.mediaType == "tv") MediaType.TV else MediaType.MOVIE) }
            .distinctBy { it.mediaType to it.id }
        val people = response.results
            .asSequence()
            .filter { it.mediaType == "person" && it.id > 0 && !it.name.isNullOrBlank() }
            .distinctBy { it.id }
            .sortedByDescending { it.popularity }
            .take(maxPeople)
            .toList()

        val rows = people.map { person ->
            val knownForItems = person.knownFor
                .asSequence()
                .filter { it.posterPath != null && (it.mediaType == "movie" || it.mediaType == "tv") }
                .sortedWith(
                    compareByDescending<TmdbMediaItem> { it.voteCount }
                        .thenByDescending { it.popularity }
                )
                .map { it.toMediaItem(if (it.mediaType == "tv") MediaType.TV else MediaType.MOVIE) }
                .distinctBy { "${it.mediaType}_${it.id}" }
                .take(20)
                .toList()
            PersonMediaSearchResult(personId = person.id, name = person.name.orEmpty(), items = knownForItems)
        }

        cacheItems(items + rows.flatMap { it.items })
        return MediaSearchResults(items, rows)
    }

    /**
     * Discover movies via TMDB discover. `releaseDate*` accepts a film with ANY release in the
     * window, `primaryReleaseDate*` only one whose FIRST release falls in it (the card's date).
     */
    suspend fun discoverMovies(
        genres: String? = null,
        sortBy: String = "popularity.desc",
        minVoteCount: Int? = null,
        page: Int = 1,
        language: String? = null,
        year: Int? = null,
        keywords: String? = null,
        releaseDateLte: String? = null,
        releaseDateGte: String? = null,
        primaryReleaseDateLte: String? = null,
        primaryReleaseDateGte: String? = null,
        minVoteAverage: Double? = null,
        maxVoteAverage: Double? = null,
        certificationCountry: String? = null,
        certificationLte: String? = null
    ): List<MediaItem> {
        val response = tmdbApi.discoverMovies(apiKey, genres = genres, sortBy = sortBy, minVoteCount = minVoteCount, page = page, originalLanguage = language, year = year, keywords = keywords, language = contentLanguage, releaseDateLte = releaseDateLte, releaseDateGte = releaseDateGte, primaryReleaseDateLte = primaryReleaseDateLte, primaryReleaseDateGte = primaryReleaseDateGte, minVoteAverage = minVoteAverage, maxVoteAverage = maxVoteAverage, certificationCountry = certificationCountry, certificationLte = certificationLte)
        val items = response.results.map { it.toMediaItem(MediaType.MOVIE) }
        cacheItems(items)
        return items
    }

    /**
     * Discover TV shows via TMDB discover. `airDate*` asks when an EPISODE aired,
     * `firstAirDate*` when the show STARTED (the card's date).
     */
    suspend fun discoverTv(
        genres: String? = null,
        sortBy: String = "popularity.desc",
        minVoteCount: Int? = null,
        page: Int = 1,
        language: String? = null,
        year: Int? = null,
        keywords: String? = null,
        airDateLte: String? = null,
        airDateGte: String? = null,
        firstAirDateLte: String? = null,
        firstAirDateGte: String? = null,
        minVoteAverage: Double? = null,
        maxVoteAverage: Double? = null
    ): List<MediaItem> {
        val response = tmdbApi.discoverTv(apiKey, genres = genres, sortBy = sortBy, minVoteCount = minVoteCount, page = page, originalLanguage = language, year = year, keywords = keywords, language = contentLanguage, airDateLte = airDateLte, airDateGte = airDateGte, firstAirDateLte = firstAirDateLte, firstAirDateGte = firstAirDateGte, minVoteAverage = minVoteAverage, maxVoteAverage = maxVoteAverage)
        val items = response.results.map { it.toMediaItem(MediaType.TV) }
        cacheItems(items)
        return items
    }

    /** TMDB reviews for a movie or TV show (cached). */
    suspend fun getReviews(mediaType: MediaType, mediaId: Int): List<Review> {
        val cacheKey = "${mediaType}_reviews_$mediaId"
        getFromCache(reviewsCache, cacheKey)?.let { return it }

        val type = if (mediaType == MediaType.TV) "tv" else "movie"
        return try {
            val response = tmdbApi.getReviews(type, mediaId, apiKey, language = contentLanguage)
            val reviews = response.results.take(10).map { review ->
                Review(
                    id = review.id,
                    author = review.author,
                    authorUsername = review.authorDetails?.username ?: "",
                    authorAvatar = review.authorDetails?.avatarPath?.let { path ->
                        // Gravatar URLs come through as "/https://…".
                        if (path.startsWith("/https://")) path.substring(1) else "${Constants.IMAGE_BASE}$path"
                    },
                    content = review.content,
                    rating = review.authorDetails?.rating,
                    createdAt = review.createdAt
                )
            }
            reviewsCache[cacheKey] = CacheEntry(reviews, System.currentTimeMillis())
            reviews
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    /** TMDB watch providers for a title, for the preferred region (then locale, then US). */
    suspend fun getStreamingServices(
        mediaType: MediaType,
        mediaId: Int,
        preferredRegion: String? = null
    ): StreamingServicesResult? {
        val region = normalizeWatchRegion(preferredRegion)
        val cacheKey = "${mediaType}_watch_providers_${mediaId}_$region"
        watchProvidersCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) return cached.data
            watchProvidersCache.remove(cacheKey)
        }

        val results = runCatching {
            when (mediaType) {
                MediaType.MOVIE -> tmdbApi.getMovieWatchProviders(mediaId, apiKey)
                MediaType.TV -> tmdbApi.getTvWatchProviders(mediaId, apiKey)
            }
        }.getOrNull()?.results.orEmpty()
        if (results.isEmpty()) {
            watchProvidersCache[cacheKey] = CacheEntry(null, System.currentTimeMillis())
            return null
        }

        val candidateRegions = listOf(region, normalizeWatchRegion(Locale.getDefault().country), "US").distinct()
        val resolved = candidateRegions.firstNotNullOfOrNull { regionKey ->
            val regionData = results.entries.firstOrNull { (key, _) -> key.equals(regionKey, ignoreCase = true) }?.value
                ?: return@firstNotNullOfOrNull null
            toStreamingServiceList(regionData).takeIf { it.isNotEmpty() }
                ?.let { StreamingServicesResult(region = regionKey, services = it) }
        } ?: results.entries.firstNotNullOfOrNull { (regionKey, regionData) ->
            toStreamingServiceList(regionData).takeIf { it.isNotEmpty() }
                ?.let { StreamingServicesResult(region = normalizeWatchRegion(regionKey), services = it) }
        }

        watchProvidersCache[cacheKey] = CacheEntry(resolved, System.currentTimeMillis())
        return resolved
    }

    private fun toStreamingServiceList(regionData: TmdbWatchProviderRegion): List<StreamingServiceInfo> {
        val prioritizedLists = listOf(
            regionData.flatrate,
            regionData.free,
            regionData.ads,
            regionData.rent,
            regionData.buy
        )
        val deduped = LinkedHashMap<String, StreamingServiceInfo>()
        prioritizedLists.forEach { providers ->
            providers
                .sortedBy { it.displayPriority }
                .forEach providerLoop@{ provider ->
                    val canonicalName = canonicalStreamingServiceName(provider.providerName)
                    if (canonicalName.isBlank()) return@providerLoop
                    val key = canonicalName.lowercase(Locale.US)
                    if (deduped.containsKey(key)) return@providerLoop
                    val stableId = provider.providerId.takeIf { it > 0 } ?: canonicalName.hashCode()
                    val logoUrl = bundledStreamingLogoUri(canonicalName)
                        ?: provider.logoPath?.let { path -> "https://image.tmdb.org/t/p/w92$path" }
                    deduped[key] = StreamingServiceInfo(id = stableId, name = canonicalName, logoUrl = logoUrl)
                }
        }
        return deduped.values.take(10)
    }

    private fun canonicalStreamingServiceName(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isBlank()) return ""
        val normalized = name.lowercase(Locale.US)
        return when {
            normalized == "max" || normalized.contains("hbo") -> "HBO Max"
            normalized.contains("netflix") -> "Netflix"
            normalized.contains("prime") || normalized.contains("amazon") -> "Prime Video"
            normalized.contains("disney") -> "Disney+"
            normalized.contains("apple tv") -> "Apple TV+"
            normalized.contains("paramount") -> "Paramount+"
            normalized.contains("hulu") -> "Hulu"
            normalized.contains("peacock") -> "Peacock"
            normalized.contains("crunchyroll") -> "Crunchyroll"
            normalized.contains("discovery") -> "Discovery+"
            normalized.contains("mgm") -> "MGM+"
            normalized.contains("shudder") -> "Shudder"
            normalized.contains("starz") -> "Starz"
            normalized.contains("youtube") -> "YouTube"
            else -> name
        }
    }

    private fun bundledStreamingLogoUri(canonicalName: String): String? {
        val resId = when (canonicalName.lowercase(Locale.US)) {
            "netflix" -> R.raw.logo_netflix
            "hbo max" -> R.raw.logo_hbo_max
            "hulu" -> R.raw.logo_hulu
            "prime video" -> R.raw.logo_prime_video
            "disney+" -> R.raw.logo_disney_plus
            "paramount+" -> R.raw.logo_paramount_plus
            "peacock" -> R.raw.logo_peacock
            "crunchyroll" -> R.raw.logo_crunchyroll
            "discovery+" -> R.raw.logo_discovery_plus
            "mgm+" -> R.raw.logo_mgm_plus
            "shudder" -> R.raw.logo_shudder
            "starz" -> R.raw.logo_starz
            "apple tv+" -> R.drawable.apple_tv_plus_logo
            else -> null
        } ?: return null
        return "android.resource://${context.packageName}/$resId"
    }

    private fun normalizeWatchRegion(region: String?): String {
        val value = region?.trim()?.uppercase(Locale.US).orEmpty()
        return value.takeIf { it.length == 2 } ?: "US"
    }
}

private fun normalizeRating(raw: String): String? {
    val value = raw.trim().replace(',', '.').toFloatOrNull() ?: return null
    if (value <= 0f || value > 10f) return null
    return String.format(Locale.US, "%.1f", value)
}

private fun formatTmdbRating(voteAverage: Float): String =
    normalizeRating(voteAverage.toString()).orEmpty()

// Extension functions to convert API responses to domain models

private fun TmdbMediaItem.toMediaItem(defaultType: MediaType): MediaItem {
    val type = when (mediaType) {
        "tv" -> MediaType.TV
        "movie" -> MediaType.MOVIE
        else -> defaultType
    }
    val dateStr = releaseDate ?: firstAirDate ?: ""
    return MediaItem(
        id = id,
        title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: "Unknown",
        subtitle = if (type == MediaType.MOVIE) "Movie" else "TV Series",
        overview = overview ?: "",
        year = dateStr.take(4),
        releaseDate = formatDate(dateStr),
        imdbRating = "",
        tmdbRating = formatTmdbRating(voteAverage),
        mediaType = type,
        image = posterPath?.let { "${Constants.IMAGE_BASE}$it" }
            ?: backdropPath?.let { "${Constants.BACKDROP_BASE}$it" }
            ?: "",
        backdrop = backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" },
        genreIds = genreIds,
        originalLanguage = originalLanguage,
        originalTitle = originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() },
        character = character ?: "",
        popularity = popularity
    )
}

private fun TmdbMovieDetails.toMediaItem(): MediaItem {
    val hours = (runtime ?: 0) / 60
    val minutes = (runtime ?: 0) % 60
    return MediaItem(
        id = id,
        title = title.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: "Unknown",
        subtitle = "Movie",
        overview = overview ?: "",
        year = releaseDate?.take(4) ?: "",
        releaseDate = formatDate(releaseDate ?: ""),
        duration = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m",
        rating = if (adult) "R" else "PG-13",
        imdbRating = "",
        tmdbRating = formatTmdbRating(voteAverage),
        mediaType = MediaType.MOVIE,
        image = posterPath?.let { "${Constants.IMAGE_BASE}$it" }
            ?: backdropPath?.let { "${Constants.BACKDROP_BASE}$it" }
            ?: "",
        backdrop = backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" },
        originalLanguage = originalLanguage,
        originalTitle = originalTitle?.takeIf { it.isNotBlank() },
        budget = budget,
        genreIds = genres.map { it.id }
    )
}

private fun TmdbTvDetails.toMediaItem(): MediaItem {
    val runtime = episodeRunTime.firstOrNull() ?: 45
    val actualSeasonCount = seasons
        .asSequence()
        .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
        .map { it.seasonNumber }
        .distinct()
        .count()
        .takeIf { it > 0 }
        ?: numberOfSeasons.coerceAtLeast(1)
    return MediaItem(
        id = id,
        title = name.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: "Unknown",
        subtitle = "TV Series",
        overview = overview ?: "",
        year = firstAirDate?.take(4) ?: "",
        releaseDate = formatDate(firstAirDate ?: ""),
        duration = "${runtime}m",
        imdbRating = "",
        tmdbRating = formatTmdbRating(voteAverage),
        mediaType = MediaType.TV,
        image = posterPath?.let { "${Constants.IMAGE_BASE}$it" }
            ?: backdropPath?.let { "${Constants.BACKDROP_BASE}$it" }
            ?: "",
        backdrop = backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" },
        originalLanguage = originalLanguage,
        originalTitle = originalName?.takeIf { it.isNotBlank() },
        isOngoing = status == "Returning Series",
        totalEpisodes = actualSeasonCount,
        status = status,
        genreIds = genres.map { it.id }
    )
}

private fun TmdbEpisode.toEpisode(): Episode = Episode(
    id = id,
    episodeNumber = episodeNumber,
    seasonNumber = seasonNumber,
    name = name,
    overview = overview ?: "",
    stillPath = stillPath?.let { "${Constants.IMAGE_BASE}$it" },
    voteAverage = voteAverage,
    runtime = runtime ?: 0,
    airDate = airDate ?: ""
)

private fun TmdbCastMember.toCastMember(): CastMember = CastMember(
    id = id,
    name = name,
    character = character ?: "",
    profilePath = profilePath?.let { "${Constants.IMAGE_BASE}$it" }
)

private fun TmdbCrewMember.toDirectorCastMember(): CastMember = CastMember(
    id = id,
    name = name,
    character = job,
    profilePath = profilePath?.let { "${Constants.IMAGE_BASE}$it" }
)

private fun TmdbPersonDetails.toPersonDetails(): PersonDetails {
    val knownFor = combinedCredits?.cast
        ?.filter { it.posterPath != null && (it.mediaType == "movie" || it.mediaType == "tv") }
        ?.sortedByDescending { it.voteCount }
        ?.take(20)
        ?.map { it.toMediaItem(if (it.mediaType == "tv") MediaType.TV else MediaType.MOVIE) }
        ?: emptyList()
    return PersonDetails(
        id = id,
        name = name,
        biography = biography ?: "",
        placeOfBirth = placeOfBirth,
        birthday = birthday,
        profilePath = profilePath?.let { "${Constants.IMAGE_BASE}$it" },
        knownFor = knownFor
    )
}

/** "yyyy-MM-dd" -> "12 Jan 2025"; returns the input unchanged when it does not parse. */
private fun formatDate(dateStr: String): String {
    if (dateStr.isEmpty()) return ""
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outputFormat = SimpleDateFormat("d MMM yyyy", Locale.US)
        inputFormat.parse(dateStr)?.let { outputFormat.format(it) } ?: dateStr
    } catch (e: Exception) {
        dateStr
    }
}
