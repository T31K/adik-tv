package com.arflix.tv.ui.screens.details

import android.content.Context
import android.util.Log
import com.arflix.tv.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.CastMember
import com.arflix.tv.data.model.Episode
import com.arflix.tv.data.model.EpisodeIdentity
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.PersonDetails
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.PlaybackProgress
import com.arflix.tv.data.repository.PlaybackProgressStore
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.util.AppLogger
import com.arflix.tv.util.Constants
import com.arflix.tv.util.EpisodeAvailability
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailsUiState(
    val isLoading: Boolean = true,
    val item: MediaItem? = null,
    val logoUrl: String? = null,
    val episodes: List<Episode> = emptyList(),
    val episodePlaybackProgress: Map<EpisodeIdentity, EpisodePlaybackProgress> = emptyMap(),
    val totalSeasons: Int = 1,
    val currentSeason: Int = 1,
    val isSeasonLoading: Boolean = false,
    val cast: List<CastMember> = emptyList(),
    val similar: List<MediaItem> = emptyList(),
    val similarLogoUrls: Map<String, String> = emptyMap(),
    val error: String? = null,
    // Person modal
    val showPersonModal: Boolean = false,
    val selectedPerson: PersonDetails? = null,
    val isLoadingPerson: Boolean = false,
    val isInWatchlist: Boolean = false,
    val showEpisodeRatings: Boolean = false,
    // Toast
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.INFO,
    // Genre names
    val genres: List<String> = emptyList(),
    // Budget (movies only)
    val budget: String? = null,
    // Initial positions for Continue Watching navigation
    val initialEpisodeIndex: Int = 0,
    val initialSeasonIndex: Int = 0,
    // Season progress: Map<seasonNumber, Pair<watchedCount, totalCount>>
    val seasonProgress: Map<Int, Pair<Int, Int>> = emptyMap(),
    val playSeason: Int? = null,
    val playEpisode: Int? = null,
    val playTmdbSeason: Int? = null,
    val playTmdbEpisode: Int? = null,
    val playLabel: String? = null,
    val playPositionMs: Long? = null,
    // TMDB collection (franchise) info — populated for movies that belong to a collection
    val collectionId: Int? = null,
    val collectionName: String? = null,
    val collectionItems: List<MediaItem> = emptyList()
)

private data class PlayTarget(
    val season: Int? = null,
    val episode: Int? = null,
    val label: String,
    val positionMs: Long? = null
)

/**
 * Per-season watched counts plus every aired episode of the show in viewing order
 * (TMDB season/episode numbers), used to pick the next episode to play.
 */
private data class SeasonProgressResult(
    val progress: Map<Int, Pair<Int, Int>>,
    val airedEpisodes: List<Pair<Int, Int>>
)

// TMDB Genre mappings
private val movieGenres = mapOf(
    28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
    80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
    14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
    9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi", 10770 to "TV Movie",
    53 to "Thriller", 10752 to "War", 37 to "Western"
)

private val tvGenres = mapOf(
    10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy",
    80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
    10762 to "Kids", 9648 to "Mystery", 10763 to "News", 10764 to "Reality",
    10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk",
    10768 to "War & Politics", 37 to "Western"
)

/**
 * Format budget number to human-readable string
 */
private fun formatBudget(budget: Long): String {
    return when {
        budget >= 1_000_000_000 -> "$${budget / 1_000_000_000.0}B"
        budget >= 1_000_000 -> "$${budget / 1_000_000}M"
        budget >= 1_000 -> "$${budget / 1_000}K"
        else -> "$$budget"
    }
}

enum class ToastType {
    SUCCESS, ERROR, INFO
}

/** Finished, either by the sticky flag or by playing past the watched threshold. */
private fun PlaybackProgress.countsAsWatched(): Boolean =
    watched || percent >= Constants.WATCHED_THRESHOLD

@HiltViewModel
class DetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val profileManager: ProfileManager,
    private val tmdbApi: TmdbApi,
    private val watchlistRepository: WatchlistRepository,
    private val playbackProgressStore: PlaybackProgressStore
) : ViewModel() {

    companion object {
        private const val TAG = "DetailsViewModel"
    }

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    private var currentMediaType: MediaType = MediaType.MOVIE
    private var currentMediaId: Int = 0
    // The episode the user last started playing from this screen, kept so that returning from the
    // player (which recreates the details screen) points the Continue button at that episode even
    // when the play was too brief to record a resume point. Scoped to a media id.
    private var lastPlayedMediaId: Int = 0
    private var lastPlayedIdentity: EpisodeIdentity? = null
    /** TMDB (season, episode) pairs of [currentMediaId] the active profile has watched. */
    private var watchedEpisodes: Set<Pair<Int, Int>> = emptySet()
    private var seasonNumbersCache: Pair<Int, List<Int>>? = null

    fun recordPlayedEpisode(mediaId: Int, identity: EpisodeIdentity?) {
        lastPlayedMediaId = mediaId
        lastPlayedIdentity = identity
    }

    // Guards overlapping season loads: only the most recently requested season may apply its result.
    private var seasonLoadJob: Job? = null
    private var seasonPrefetchJob: Job? = null
    private var seasonLoadRequestedSeason: Int = -1
    @Volatile private var initialLoadComplete = false

    private fun showBudgetKey() = profileManager.profileBooleanKey("show_budget_on_home")
    private fun showEpisodeRatingsKey() = profileManager.profileBooleanKey("show_episode_ratings")

    private fun isBlankRating(value: String): Boolean {
        return value.isBlank() || value == "0.0" || value == "0"
    }

    private fun mergeItem(primary: MediaItem, fallback: MediaItem?): MediaItem {
        if (fallback == null) return primary
        return primary.copy(
            title = primary.title.ifBlank { fallback.title },
            subtitle = primary.subtitle.ifBlank { fallback.subtitle },
            overview = primary.overview.ifBlank { fallback.overview },
            year = primary.year.ifBlank { fallback.year },
            releaseDate = primary.releaseDate ?: fallback.releaseDate,
            rating = primary.rating.ifBlank { fallback.rating },
            contentRating = primary.contentRating ?: fallback.contentRating,
            duration = primary.duration.ifBlank { fallback.duration },
            imdbRating = if (isBlankRating(primary.imdbRating)) fallback.imdbRating else primary.imdbRating,
            tmdbRating = if (isBlankRating(primary.tmdbRating)) fallback.tmdbRating else primary.tmdbRating,
            image = primary.image.ifBlank { fallback.image },
            backdrop = primary.backdrop ?: fallback.backdrop,
            primaryNetworkLogo = primary.primaryNetworkLogo ?: fallback.primaryNetworkLogo,
            genreIds = if (primary.genreIds.isEmpty()) fallback.genreIds else primary.genreIds,
            originalLanguage = primary.originalLanguage ?: fallback.originalLanguage,
            originalTitle = primary.originalTitle ?: fallback.originalTitle,
            isOngoing = primary.isOngoing || fallback.isOngoing,
            totalEpisodes = primary.totalEpisodes ?: fallback.totalEpisodes,
            watchedEpisodes = primary.watchedEpisodes ?: fallback.watchedEpisodes,
            budget = primary.budget ?: fallback.budget,
            revenue = primary.revenue ?: fallback.revenue,
            status = primary.status ?: fallback.status
        )
    }

    fun loadDetails(mediaType: MediaType, mediaId: Int, initialSeason: Int? = null, initialEpisode: Int? = null) {
        val sameMedia = currentMediaId == mediaId && currentMediaType == mediaType
        currentMediaType = mediaType
        currentMediaId = mediaId
        initialLoadComplete = false
        if (!sameMedia) watchedEpisodes = emptySet()
        seasonLoadJob?.cancel()
        seasonPrefetchJob?.cancel()
        seasonLoadRequestedSeason = -1

        viewModelScope.launch {
            try {
                val prefs = context.settingsDataStore.data.first()
                val showBudget = prefs[showBudgetKey()] ?: true
                val showEpisodeRatings = prefs[showEpisodeRatingsKey()] ?: false

                val previousState = _uiState.value
                val previousMatches = previousState.item?.id == mediaId &&
                    previousState.item?.mediaType == mediaType
                // When re-entering the same show (e.g. the details screen was recreated behind the
                // player), the ViewModel survives in the back stack but the nav-arg episode target is
                // stale (frozen at the original deeplink). Prefer the episode the user last played
                // (recorded even for brief plays) so the Continue button follows it. The season rail
                // keeps whatever season the user was browsing (previousState.currentSeason).
                val reentry = previousMatches && previousState.currentSeason > 0
                val playedIdentity = lastPlayedIdentity?.takeIf { lastPlayedMediaId == mediaId }
                val targetSeason = if (reentry) playedIdentity?.tmdbSeason else initialSeason
                val targetEpisode = if (reentry) playedIdentity?.tmdbEpisode else initialEpisode
                val seasonToLoad = if (reentry) previousState.currentSeason else targetSeason ?: 1
                val hasExplicitEpisodeTarget = mediaType == MediaType.TV && targetSeason != null && targetEpisode != null

                val cachedFullItem = mediaRepository.getCachedFullItem(mediaType, mediaId)
                val cachedItem = cachedFullItem
                    ?: mediaRepository.getCachedItem(mediaType, mediaId)
                    ?: mediaRepository.getCachedItemFromDisk(mediaType, mediaId)
                val previousItem = previousState.item?.takeIf { previousMatches }
                val initialItem = cachedItem ?: previousItem
                val cachedLogoUrl = mediaRepository.peekCachedLogoUrl(mediaType, mediaId)
                    ?: previousState.logoUrl?.takeIf { previousMatches }
                val cachedEpisodes = if (mediaType == MediaType.TV) {
                    mediaRepository.peekCachedSeasonEpisodes(mediaId, seasonToLoad)
                        ?: previousState.episodes.takeIf { previousMatches && previousState.currentSeason == seasonToLoad }
                } else null
                val cachedTotalSeasons = if (mediaType == MediaType.TV) {
                    cachedFullItem?.totalEpisodes?.coerceAtLeast(1)
                        ?: previousState.totalSeasons.takeIf { previousMatches && !previousState.isLoading }
                        ?: 1
                } else {
                    1
                }

                _uiState.value = DetailsUiState(
                    isLoading = initialItem == null,
                    item = initialItem,
                    logoUrl = cachedLogoUrl,
                    episodes = cachedEpisodes?.let(::decorateWatched) ?: emptyList(),
                    currentSeason = seasonToLoad,
                    totalSeasons = cachedTotalSeasons,
                    playSeason = targetSeason,
                    playEpisode = targetEpisode,
                    playTmdbSeason = targetSeason,
                    playTmdbEpisode = targetEpisode,
                    playLabel = if (hasExplicitEpisodeTarget) {
                        context.getString(R.string.continue_season_episode, targetSeason, targetEpisode)
                    } else {
                        null
                    },
                    showEpisodeRatings = showEpisodeRatings
                )

                fun logDetailsLoadFailure(label: String, throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    Log.w(TAG, "Failed to load details $label", throwable)
                }

                suspend fun <T> loadDetailsPart(label: String, block: suspend () -> T): T? {
                    return runCatching { block() }
                        .onFailure { logDetailsLoadFailure(label, it) }
                        .getOrNull()
                }

                fun isCurrentRequest(): Boolean {
                    return currentMediaId == mediaId && currentMediaType == mediaType
                }
                fun updateState(block: (DetailsUiState) -> DetailsUiState) {
                    if (!isCurrentRequest()) return
                    _uiState.value = block(_uiState.value)
                }

                val itemDeferred = async {
                    loadDetailsPart("item") {
                        if (mediaType == MediaType.TV) {
                            mediaRepository.getTvDetails(mediaId)
                        } else {
                            mediaRepository.getMovieDetails(mediaId)
                        }
                    }
                }
                val watchlistDeferred = async {
                    loadDetailsPart("watchlist") {
                        watchlistRepository.isInWatchlist(mediaType, mediaId)
                    } ?: false
                }
                // Fetch logo URL concurrently with details to avoid ~1s delay
                val logoDeferred = async { loadDetailsPart("logo") { mediaRepository.getLogoUrl(mediaType, mediaId) } }
                val episodesDeferred = if (mediaType == MediaType.TV) {
                    async<List<Episode>> {
                        loadDetailsPart("season $seasonToLoad episodes") {
                            mediaRepository.getSeasonEpisodes(mediaId, seasonToLoad)
                        } ?: emptyList()
                    }
                } else null
                val progressDeferred = async<List<PlaybackProgress>> {
                    loadDetailsPart("playback progress") {
                        if (mediaType == MediaType.TV) {
                            playbackProgressStore.episodesFor(mediaId)
                        } else {
                            listOfNotNull(playbackProgressStore.get(mediaId, MediaType.MOVIE, null, null))
                        }
                    } ?: emptyList()
                }

                val loadedItem = runCatching { itemDeferred.await() }.getOrNull()
                val item = loadedItem ?: initialItem
                if (item == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(R.string.details_error_load_failed)
                    )
                    return@launch
                }
                val mergedItem = mergeItem(item, initialItem).let { m ->
                    // Megaflix: metadata re-fetch by TMDB id drops localUri; restore it
                    // from the local library so Play knows this is a local file.
                    val localUri = m.localUri ?: mediaRepository.localUriForId(mediaId)
                    if (localUri != null) m.copy(localUri = localUri) else m
                }
                val hasTrustedTvDetails = mediaType != MediaType.TV || loadedItem != null || cachedFullItem != null
                val resolvedTotalSeasons = if (mediaType == MediaType.TV && hasTrustedTvDetails) {
                    mergedItem.totalEpisodes?.coerceAtLeast(1) ?: 1
                } else {
                    1
                }

                val progressEntries = runCatching { progressDeferred.await() }.getOrDefault(emptyList())
                if (mediaType == MediaType.TV) watchedEpisodes = watchedSetOf(progressEntries)
                val isWatched = if (mediaType == MediaType.MOVIE) {
                    progressEntries.firstOrNull()?.countsAsWatched() == true
                } else {
                    watchedEpisodes.isNotEmpty()
                }

                val episodes = decorateWatched(runCatching { episodesDeferred?.await() }.getOrNull().orEmpty())
                val targetEpisodeForRow = if (targetSeason == seasonToLoad) targetEpisode else null
                val nextUnwatchedEpisode = episodes.firstOrNull { !it.isWatched }
                // Focus the explicit target episode if the caller passed one (deeplink /
                // Continue Watching tile), otherwise focus the first unwatched episode so
                // users don't have to scroll past every episode they've already seen.
                val initialEpisodeIndex = when {
                    targetEpisodeForRow != null ->
                        episodes.indexOfFirst { it.tmdbEpisodeNumber == targetEpisodeForRow }.coerceAtLeast(0)
                    nextUnwatchedEpisode != null -> episodes.indexOf(nextUnwatchedEpisode).coerceAtLeast(0)
                    else -> 0
                }

                // Map genre IDs to names
                val genreMap = if (mediaType == MediaType.TV) tvGenres else movieGenres
                val genreNames = mergedItem.genreIds.mapNotNull { genreMap[it] }.take(4)

                // Format budget for movies
                val budgetDisplay = if (mediaType == MediaType.MOVIE && mergedItem.budget != null && mergedItem.budget > 0) {
                    formatBudget(mergedItem.budget)
                } else null

                val playTarget = if (mediaType == MediaType.MOVIE) {
                    moviePlayTarget(progressEntries.firstOrNull())
                } else {
                    tvPlayTarget(progressEntries, targetSeason, targetEpisode, null, episodes)
                }

                updateState { state ->
                    state.copy(
                        isLoading = false,
                        item = mergedItem.copy(isWatched = isWatched),
                        totalSeasons = resolvedTotalSeasons,
                        currentSeason = seasonToLoad,
                        episodes = episodes,
                        episodePlaybackProgress = episodePlaybackProgress(mediaId, episodes, progressEntries),
                        initialEpisodeIndex = initialEpisodeIndex,
                        initialSeasonIndex = (seasonToLoad - 1).coerceAtLeast(0),
                        genres = genreNames,
                        budget = if (showBudget) budgetDisplay else null
                    ).withPlayTarget(playTarget, mediaType)
                }
                initialLoadComplete = true

                if (mediaType == MediaType.TV) {
                    prefetchAdjacentSeasons(mediaId, seasonToLoad, resolvedTotalSeasons)
                    // Season counts + the "next episode" target need every season's episode list.
                    launch(Dispatchers.IO) {
                        val progress = fetchSeasonProgress(mediaId) ?: return@launch
                        val target = tvPlayTarget(progressEntries, targetSeason, targetEpisode, progress, episodes)
                        updateState { state ->
                            state.copy(
                                seasonProgress = progress.progress,
                                totalSeasons = maxOf(state.totalSeasons, progress.progress.keys.maxOrNull() ?: 0, 1)
                            ).withPlayTarget(target, mediaType)
                        }
                    }
                }

                // Logo URL was fetched concurrently with details via logoDeferred
                launch {
                    val logoUrl = runCatching { logoDeferred.await() }.getOrNull()
                    if (logoUrl != null && logoUrl != _uiState.value.logoUrl) {
                        updateState { state -> state.copy(logoUrl = logoUrl) }
                    }
                }

                launch {
                    delay(220L)
                    val cast = runCatching { mediaRepository.getCast(mediaType, mediaId) }.getOrNull()
                    if (!cast.isNullOrEmpty()) {
                        updateState { state -> state.copy(cast = cast) }
                    }
                }

                launch {
                    delay(320L)
                    val similar = runCatching { mediaRepository.getSimilar(mediaType, mediaId) }.getOrNull()
                    if (!similar.isNullOrEmpty()) {
                        val logos = similar.take(8).map { item ->
                            async {
                                val key = "${item.mediaType}_${item.id}"
                                val logo = runCatching {
                                    mediaRepository.getLogoUrl(item.mediaType, item.id)
                                }.getOrNull()
                                if (logo.isNullOrBlank()) null else key to logo
                            }
                        }.mapNotNull { runCatching { it.await() }.getOrNull() }.toMap()
                        updateState { state ->
                            state.copy(
                                similar = similar,
                                similarLogoUrls = logos
                            )
                        }
                    }
                }

                // TMDB collection (franchise) — only for movies
                if (mediaType == MediaType.MOVIE) {
                    launch {
                        val collectionRef = runCatching {
                            mediaRepository.getMovieCollectionRef(mediaId)
                        }.getOrNull() ?: return@launch
                        updateState { state ->
                            state.copy(
                                collectionId = collectionRef.id,
                                collectionName = collectionRef.name
                            )
                        }
                        val items = runCatching {
                            mediaRepository.getTmdbCollectionItems(collectionRef.id)
                        }.getOrNull() ?: emptyList()
                        updateState { state ->
                            if (state.collectionId == collectionRef.id) {
                                state.copy(collectionItems = items)
                            } else state
                        }
                    }
                }

                launch {
                    val isInWatchlist = runCatching { watchlistDeferred.await() }.getOrDefault(false)
                    updateState { state -> state.copy(isInWatchlist = isInWatchlist) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun loadSeason(seasonNumber: Int) {
        if (currentMediaType != MediaType.TV) return
        // Don't reload if already on this season with loaded episodes and not in loading state
        if (_uiState.value.currentSeason == seasonNumber && _uiState.value.episodes.isNotEmpty() && !_uiState.value.isSeasonLoading) return
        // Ignore a duplicate request for a load that is already in flight
        if (seasonLoadRequestedSeason == seasonNumber && seasonLoadJob?.isActive == true) return

        seasonLoadJob?.cancel()
        seasonLoadRequestedSeason = seasonNumber

        // Fast-path: Check in-memory cache synchronously for instant 0ms transition!
        val cachedEpisodes = mediaRepository.peekCachedSeasonEpisodes(currentMediaId, seasonNumber)
        if (!cachedEpisodes.isNullOrEmpty()) {
            val decorated = decorateWatched(cachedEpisodes)
            _uiState.value = _uiState.value.copy(
                currentSeason = seasonNumber,
                episodes = decorated,
                episodePlaybackProgress = emptyMap(),
                isSeasonLoading = false
            )
            refreshEpisodePlaybackProgress(decorated)
            prefetchAdjacentSeasons(currentMediaId, seasonNumber, _uiState.value.totalSeasons)
            return
        }

        // Cache miss: Optimistically update selected season pill immediately and show loading skeleton
        _uiState.value = _uiState.value.copy(
            currentSeason = seasonNumber,
            episodes = emptyList(),
            episodePlaybackProgress = emptyMap(),
            isSeasonLoading = true
        )

        seasonLoadJob = viewModelScope.launch {
            try {
                val episodes = mediaRepository.getSeasonEpisodes(currentMediaId, seasonNumber)
                // A newer request superseded this one while we were fetching — drop the stale result.
                if (seasonLoadRequestedSeason != seasonNumber) return@launch
                if (episodes.isNotEmpty()) {
                    val decorated = decorateWatched(episodes)
                    _uiState.value = _uiState.value.copy(
                        episodes = decorated,
                        currentSeason = seasonNumber,
                        isSeasonLoading = false
                    )
                    refreshEpisodePlaybackProgress(decorated)
                    prefetchAdjacentSeasons(currentMediaId, seasonNumber, _uiState.value.totalSeasons)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSeasonLoading = false,
                        toastMessage = context.getString(R.string.details_no_episodes_season, seasonNumber),
                        toastType = ToastType.ERROR
                    )
                }
            } catch (e: CancellationException) {
                // Superseded by a newer season request
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSeasonLoading = false,
                    toastMessage = context.getString(R.string.details_failed_load_season, seasonNumber),
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    private fun refreshEpisodePlaybackProgress(episodes: List<Episode>) {
        val mediaId = currentMediaId
        if (mediaId == 0 || currentMediaType != MediaType.TV || episodes.isEmpty()) return
        val identities = episodes.mapTo(hashSetOf()) { it.identity }
        viewModelScope.launch {
            val entries = readEpisodeProgress(mediaId)
            if (currentMediaId != mediaId || currentMediaType != MediaType.TV) return@launch
            if (_uiState.value.episodes.mapTo(hashSetOf()) { it.identity } != identities) return@launch
            _uiState.value = _uiState.value.copy(
                episodePlaybackProgress = episodePlaybackProgress(mediaId, episodes, entries)
            )
        }
    }

    private fun prefetchAdjacentSeasons(mediaId: Int, selectedSeason: Int, totalSeasons: Int) {
        seasonPrefetchJob?.cancel()
        val seasons = listOf(selectedSeason - 1, selectedSeason + 1)
            .filter { it in 1..totalSeasons }
        if (seasons.isEmpty()) return

        seasonPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            seasons.forEach { season ->
                try {
                    mediaRepository.getSeasonEpisodes(mediaId, season)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Megaflix: local file for a specific episode of this show (flat model). Null if not downloaded. */
    fun megaflixEpisodePath(tmdbSeason: Int?, tmdbEpisode: Int?): String? {
        if (tmdbSeason == null || tmdbEpisode == null) return null
        return mediaRepository.megaflixEpisodePath(currentMediaId, tmdbSeason, tmdbEpisode)
    }

    fun resolveEpisodeIdentity(
        displaySeason: Int?,
        displayEpisode: Int?,
        tmdbSeason: Int?,
        tmdbEpisode: Int?
    ): EpisodeIdentity? {
        if (displaySeason == null || displayEpisode == null || tmdbSeason == null || tmdbEpisode == null) {
            return null
        }
        return EpisodeIdentity(displaySeason, displayEpisode, tmdbSeason, tmdbEpisode)
    }

    fun toggleWatchlist() {
        val currentItem = _uiState.value.item ?: return
        val newInWatchlist = !_uiState.value.isInWatchlist

        viewModelScope.launch {
            try {
                if (newInWatchlist) {
                    // Pass the full MediaItem so it appears instantly in watchlist
                    watchlistRepository.addToWatchlist(currentMediaType, currentMediaId, currentItem)
                } else {
                    watchlistRepository.removeFromWatchlist(currentMediaType, currentMediaId)
                }
                _uiState.value = _uiState.value.copy(
                    isInWatchlist = newInWatchlist,
                    toastMessage = if (newInWatchlist) context.getString(R.string.added_to_watchlist) else context.getString(R.string.watchlist_toast_removed),
                    toastType = ToastType.SUCCESS
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error updating watchlist: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    toastMessage = context.getString(R.string.details_failed_update_watchlist),
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun dismissToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    /**
     * Refresh watched badges, per-episode progress and the Play/Resume target when the
     * screen resumes (e.g. returning from the player). Everything comes from the local
     * [PlaybackProgressStore].
     */
    fun refreshAfterPlayerReturn() {
        if (currentMediaId == 0) return
        // Don't run during initial load — would overwrite episodes/seasonProgress with empty data.
        if (_uiState.value.isLoading || !initialLoadComplete) return
        viewModelScope.launch { applyStoredProgress() }
    }

    fun markEpisodeWatched(season: Int, episode: Int, watched: Boolean) {
        if (currentMediaType != MediaType.TV) return
        viewModelScope.launch {
            try {
                val selectedEpisode = _uiState.value.episodes.firstOrNull {
                    it.seasonNumber == season && it.episodeNumber == episode
                }
                playbackProgressStore.setWatched(
                    tmdbId = currentMediaId,
                    mediaType = MediaType.TV,
                    season = selectedEpisode?.tmdbSeasonNumber ?: season,
                    episode = selectedEpisode?.tmdbEpisodeNumber ?: episode,
                    watched = watched,
                    title = _uiState.value.item?.title.orEmpty()
                )
                applyStoredProgress()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = context.getString(R.string.details_failed_update_watched),
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun markSeasonWatched(season: Int) = setSeasonWatched(season, watched = true)

    fun markSeasonUnwatched(season: Int) = setSeasonWatched(season, watched = false)

    private fun setSeasonWatched(season: Int, watched: Boolean) {
        if (currentMediaType != MediaType.TV) return
        val title = _uiState.value.item?.title ?: return

        viewModelScope.launch {
            try {
                val seasonEpisodes = if (_uiState.value.currentSeason == season && _uiState.value.episodes.isNotEmpty()) {
                    _uiState.value.episodes
                } else {
                    mediaRepository.getSeasonEpisodes(currentMediaId, season)
                }
                val targets = if (watched) {
                    seasonEpisodes.filter { EpisodeAvailability.hasAired(it.airDate) }
                } else {
                    seasonEpisodes
                }
                if (targets.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        toastMessage = context.getString(R.string.details_no_episodes_season, season),
                        toastType = ToastType.ERROR
                    )
                    return@launch
                }
                targets.forEach { ep ->
                    playbackProgressStore.setWatched(
                        tmdbId = currentMediaId,
                        mediaType = MediaType.TV,
                        season = ep.tmdbSeasonNumber,
                        episode = ep.tmdbEpisodeNumber,
                        watched = watched,
                        title = title
                    )
                }
                applyStoredProgress()
                _uiState.value = _uiState.value.copy(
                    toastMessage = context.getString(
                        if (watched) R.string.details_season_marked_watched else R.string.details_season_marked_unwatched,
                        season
                    ),
                    toastType = ToastType.SUCCESS
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = context.getString(
                        if (watched) R.string.details_failed_mark_season_watched else R.string.details_failed_mark_season_unwatched
                    ),
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    // ========== Person Modal ==========

    fun loadPerson(personId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                showPersonModal = true,
                isLoadingPerson = true,
                selectedPerson = null
            )

            try {
                val person = mediaRepository.getPersonDetails(personId)
                _uiState.value = _uiState.value.copy(
                    isLoadingPerson = false,
                    selectedPerson = person
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingPerson = false
                )
            }
        }
    }

    fun closePersonModal() {
        _uiState.value = _uiState.value.copy(
            showPersonModal = false,
            selectedPerson = null
        )
    }

    // ========== Local playback progress ==========

    private suspend fun readEpisodeProgress(tmdbId: Int): List<PlaybackProgress> =
        try {
            playbackProgressStore.episodesFor(tmdbId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    private fun watchedSetOf(entries: List<PlaybackProgress>): Set<Pair<Int, Int>> =
        entries.mapNotNullTo(hashSetOf()) { entry ->
            val season = entry.season ?: return@mapNotNullTo null
            val episode = entry.episode ?: return@mapNotNullTo null
            if (entry.countsAsWatched()) season to episode else null
        }

    private fun decorateWatched(episodes: List<Episode>): List<Episode> =
        episodes.map { ep ->
            val watched = (ep.tmdbSeasonNumber to ep.tmdbEpisodeNumber) in watchedEpisodes
            if (ep.isWatched == watched) ep else ep.copy(isWatched = watched)
        }

    /**
     * Re-reads the store for the current title and applies watched badges, episode
     * progress bars, season counts and the Play/Resume target in one state write.
     */
    private suspend fun applyStoredProgress() {
        val tmdbId = currentMediaId
        val mediaType = currentMediaType
        fun isCurrent() = currentMediaId == tmdbId && currentMediaType == mediaType

        if (mediaType == MediaType.MOVIE) {
            val entry = try {
                playbackProgressStore.get(tmdbId, MediaType.MOVIE, null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (!isCurrent()) return
            val state = _uiState.value
            _uiState.value = state.copy(
                item = state.item?.copy(isWatched = entry?.countsAsWatched() == true)
            ).withPlayTarget(moviePlayTarget(entry), mediaType)
            return
        }

        val entries = readEpisodeProgress(tmdbId)
        if (!isCurrent()) return
        watchedEpisodes = watchedSetOf(entries)
        val progress = fetchSeasonProgress(tmdbId)
        if (!isCurrent()) return
        val state = _uiState.value
        val episodes = decorateWatched(state.episodes)
        val target = tvPlayTarget(entries, null, null, progress, episodes)
        _uiState.value = state.copy(
            item = state.item?.copy(isWatched = watchedEpisodes.isNotEmpty()),
            episodes = episodes,
            episodePlaybackProgress = episodePlaybackProgress(tmdbId, episodes, entries),
            seasonProgress = progress?.progress ?: state.seasonProgress
        ).withPlayTarget(target, mediaType)
    }

    /**
     * Watched/aired counts for every season, from TMDB episode lists (cached by
     * MediaRepository) and [watchedEpisodes]. Null if TMDB is unreachable.
     */
    private suspend fun fetchSeasonProgress(tmdbId: Int): SeasonProgressResult? {
        return try {
            val seasonNumbers = seasonNumbersCache?.takeIf { it.first == tmdbId }?.second ?: run {
                val tvDetails = tmdbApi.getTvDetails(tmdbId, Constants.TMDB_API_KEY)
                tvDetails.seasons
                    .asSequence()
                    .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
                    .map { it.seasonNumber }
                    .distinct()
                    .sorted()
                    .toList()
                    .ifEmpty { (1..tvDetails.numberOfSeasons.coerceAtLeast(1)).toList() }
                    .also { seasonNumbersCache = tmdbId to it }
            }
            val watched = watchedEpisodes
            val progressMap = mutableMapOf<Int, Pair<Int, Int>>()
            val aired = mutableListOf<Pair<Int, Int>>()
            for (seasonNum in seasonNumbers) {
                val episodes = try {
                    mediaRepository.getSeasonEpisodes(tmdbId, seasonNum)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                val airedCoords = episodes
                    .filter { EpisodeAvailability.hasAired(it.airDate) }
                    .map { it.tmdbSeasonNumber to it.tmdbEpisodeNumber }
                    .sortedBy { it.second }
                progressMap[seasonNum] = Pair(airedCoords.count { it in watched }, airedCoords.size)
                aired += airedCoords
            }
            SeasonProgressResult(progress = progressMap, airedEpisodes = aired)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load season progress", e)
            null
        }
    }

    private fun moviePlayTarget(entry: PlaybackProgress?): PlayTarget? {
        val resume = entry?.takeIf { it.isResumable } ?: return null
        return PlayTarget(
            label = context.getString(R.string.continue_at, formatResumeTime(resume.positionMs / 1000L)),
            positionMs = resume.positionMs
        )
    }

    /**
     * Series Play/Resume target:
     * 1. an explicit episode (Continue Watching deeplink / last played), resuming it if it has progress;
     * 2. the most recently played episode, if it is still resumable;
     * 3. the next unwatched aired episode after the most recently played one;
     * 4. S1E1.
     * [progress] is null until every season's episode list is known; the loaded
     * [episodes] are the fallback order then.
     */
    private fun tvPlayTarget(
        entries: List<PlaybackProgress>,
        preferredSeason: Int?,
        preferredEpisode: Int?,
        progress: SeasonProgressResult?,
        episodes: List<Episode>
    ): PlayTarget? {
        val episodeEntries = entries.filter { it.season != null && it.episode != null }
        if (preferredSeason != null && preferredEpisode != null) {
            val exact = episodeEntries.firstOrNull { it.season == preferredSeason && it.episode == preferredEpisode }
            return exact?.takeIf { it.isResumable }?.let(::episodeResumeTarget)
                ?: PlayTarget(
                    season = preferredSeason,
                    episode = preferredEpisode,
                    label = context.getString(R.string.continue_season_episode, preferredSeason, preferredEpisode)
                )
        }

        val latest = episodeEntries.maxByOrNull { it.updatedAtMs }
        if (latest != null && latest.isResumable) return episodeResumeTarget(latest)

        val order = progress?.airedEpisodes
            ?: episodes.filter { EpisodeAvailability.hasAired(it.airDate) }
                .map { it.tmdbSeasonNumber to it.tmdbEpisodeNumber }
        if (order.isEmpty()) return null
        if (latest == null && watchedEpisodes.isEmpty()) {
            val first = order.first()
            return PlayTarget(
                season = first.first,
                episode = first.second,
                label = if (first == (1 to 1)) {
                    context.getString(R.string.play_start_s1e1)
                } else {
                    context.getString(R.string.play_season_episode, first.first, first.second)
                }
            )
        }
        val startIndex = latest?.let { order.indexOf(it.season!! to it.episode!!) } ?: -1
        val next = order.drop(startIndex + 1).firstOrNull { it !in watchedEpisodes }
            ?: order.firstOrNull { it !in watchedEpisodes }
            // Everything watched: offer a restart.
            ?: return PlayTarget(season = 1, episode = 1, label = context.getString(R.string.play_start_s1e1))
        return PlayTarget(
            season = next.first,
            episode = next.second,
            label = context.getString(R.string.continue_season_episode, next.first, next.second)
        )
    }

    private fun episodeResumeTarget(entry: PlaybackProgress): PlayTarget {
        val season = entry.season ?: 1
        val episode = entry.episode ?: 1
        return PlayTarget(
            season = season,
            episode = episode,
            label = context.getString(
                R.string.continue_season_episode_at,
                season,
                episode,
                formatResumeTime(entry.positionMs / 1000L)
            ),
            positionMs = entry.positionMs
        )
    }

    /** Movies: a null target resets the button to plain "Play". Series keep the last target. */
    private fun DetailsUiState.withPlayTarget(target: PlayTarget?, mediaType: MediaType): DetailsUiState {
        if (target == null && mediaType == MediaType.TV) return this
        return copy(
            playSeason = target?.season,
            playEpisode = target?.episode,
            playTmdbSeason = target?.season,
            playTmdbEpisode = target?.episode,
            playLabel = target?.label,
            playPositionMs = target?.positionMs
        )
    }

    private fun formatResumeTime(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%d:%02d".format(minutes, secs)
        }
    }
}
