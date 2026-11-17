package com.arflix.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.megaflix.MegaflixLibraryBuilder
import com.arflix.tv.megaflix.MegaflixSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Ids of the two browse rows shown while the query is empty; SearchScreen localizes their titles. */
internal const val LIBRARY_MOVIES_ROW_ID = "library_movies"
internal const val LIBRARY_TV_ROW_ID = "library_tv"

// Memoized empty collections to reduce GC pressure
private val EMPTY_MEDIA_ITEMS: List<MediaItem> = emptyList()
private val EMPTY_CATEGORIES: List<Category> = emptyList()
private val EMPTY_LOGO_URLS: Map<String, String> = emptyMap()

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<MediaItem> = EMPTY_MEDIA_ITEMS,
    val movieResults: List<MediaItem> = EMPTY_MEDIA_ITEMS,
    val tvResults: List<MediaItem> = EMPTY_MEDIA_ITEMS,
    val personResults: List<Category> = EMPTY_CATEGORIES,
    val cardLogoUrls: Map<String, String> = EMPTY_LOGO_URLS,
    val error: String? = null,
    // Browse rows (the local library) shown while the query is empty
    val discoverCategories: List<Category> = EMPTY_CATEGORIES,
    val discoverLogoUrls: Map<String, String> = EMPTY_LOGO_URLS,
    val isDiscoverLoading: Boolean = false
)

/**
 * Search over the ADIK library: only titles that are in our feed can be watched, so every
 * result is a library item. TMDB still does the matching (fuzzy titles, translated names,
 * people); its hits are then narrowed to the library, and a plain local title match covers
 * anything TMDB missed or an offline TMDB.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val libraryBuilder: MegaflixLibraryBuilder,
    private val syncManager: MegaflixSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var logoJob: Job? = null
    private var activeSearchQuery: String? = null
    private var completedQuery: String? = null

    /** The library by (type, TMDB id); rebuilt whenever the feed changes. */
    @Volatile private var library: Map<Pair<MediaType, Int>, MediaItem> = emptyMap()

    init {
        viewModelScope.launch {
            syncManager.feedItems.collectLatest { loadLibrary() }
        }
    }

    // ── Library rows ────────────────────────────────────────────────────

    private suspend fun loadLibrary() {
        _uiState.update { it.copy(isDiscoverLoading = it.discoverCategories.isEmpty()) }
        val (movies, shows) = try {
            libraryBuilder.libraryItems(MediaType.MOVIE) to libraryBuilder.libraryItems(MediaType.TV)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { EMPTY_MEDIA_ITEMS to EMPTY_MEDIA_ITEMS }

        library = (movies + shows).associateBy { it.mediaType to it.id }
        (movies + shows).forEach { mediaRepository.cacheItem(it) }
        // Row titles stay English: SearchScreen localizes them by id for display.
        val rows = listOfNotNull(
            movies.takeIf { it.isNotEmpty() }?.let { Category(LIBRARY_MOVIES_ROW_ID, "Movies", it) },
            shows.takeIf { it.isNotEmpty() }?.let { Category(LIBRARY_TV_ROW_ID, "TV Shows", it) }
        )
        _uiState.update { it.copy(discoverCategories = rows, isDiscoverLoading = false) }
        loadRowLogos(rows)

        // A query typed before the library arrived was filtered against an empty library.
        if (_uiState.value.query.isNotBlank()) {
            completedQuery = null
            search()
        }
    }

    private fun loadRowLogos(rows: List<Category>) {
        logoJob?.cancel()
        logoJob = viewModelScope.launch(Dispatchers.IO) {
            val slots = Semaphore(3)
            val known = _uiState.value.discoverLogoUrls
            val items = rows.flatMap { it.items }
                .filterNot { "${it.mediaType}_${it.id}" in known }
                .take(24)
            val logos = items.map { item ->
                async {
                    val logo = slots.withPermit { fetchLogo(item) }
                    if (!logo.isNullOrBlank()) {
                        mediaRepository.cacheLogoUrl(item.mediaType, item.id, logo)
                        "${item.mediaType}_${item.id}" to logo
                    } else null
                }
            }.awaitAll().filterNotNull().toMap()
            _uiState.update { it.copy(discoverLogoUrls = it.discoverLogoUrls + logos) }
        }
    }

    private suspend fun fetchLogo(item: MediaItem): String? = try {
        withTimeoutOrNull(2_000) {
            withContext(Dispatchers.IO) { mediaRepository.getLogoUrl(item.mediaType, item.id) }
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    // ── Search ──────────────────────────────────────────────────────────

    fun updateQuery(newQuery: String) {
        if (newQuery == _uiState.value.query) return
        searchJob?.cancel()
        activeSearchQuery = null
        completedQuery = null
        _uiState.value = _uiState.value.copy(query = newQuery)
        if (newQuery.trim().isEmpty()) {
            clearSearch()
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, error = null, results = EMPTY_MEDIA_ITEMS,
            movieResults = EMPTY_MEDIA_ITEMS, tvResults = EMPTY_MEDIA_ITEMS,
            personResults = EMPTY_CATEGORIES, cardLogoUrls = EMPTY_LOGO_URLS)
        debounceSearch()
    }

    fun search() {
        val query = _uiState.value.query.trim(); if (query.isEmpty()) return
        if (searchJob?.isActive == true && activeSearchQuery == query && _uiState.value.error == null) return
        if (completedQuery == query && _uiState.value.error == null) return
        searchJob?.cancel()
        activeSearchQuery = query
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.results.isEmpty(), error = null) }
            val lib = library
            fun inLibrary(items: List<MediaItem>) =
                items.mapNotNull { lib[it.mediaType to it.id] }.distinctBy { it.mediaType to it.id }

            val response = try {
                withContext(Dispatchers.IO) { mediaRepository.searchWithPeople(query) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }

            val tmdbHits = inLibrary(response?.items.orEmpty())
            val localHits = lib.values.filter { libraryTitleMatches(query, it.title) }
            val sorted = withContext(Dispatchers.Default) { rankSearchResults(query, tmdbHits + localHits) }
            var peopleRows = response?.people.orEmpty()
                .mapNotNull { person ->
                    inLibrary(person.items).takeIf { it.isNotEmpty() }
                        ?.let { Category("person_${person.personId}", person.name, it) }
                }
            val peopleNeedingCredits = response?.people.orEmpty().filter { it.items.isEmpty() }
            completedQuery = query.takeIf { response != null }
            _uiState.update {
                it.copy(
                    isLoading = sorted.isEmpty() && peopleRows.isEmpty() && peopleNeedingCredits.isNotEmpty(),
                    results = sorted,
                    movieResults = sorted.filter { item -> item.mediaType == MediaType.MOVIE },
                    tvResults = sorted.filter { item -> item.mediaType == MediaType.TV },
                    personResults = peopleRows,
                    error = if (response == null && sorted.isEmpty()) "search failed" else null
                )
            }

            // Cards are usable now. Bounded, cancellable enrichment never replaces the rows.
            val slots = Semaphore(3)
            val top = (sorted + peopleRows.flatMap { it.items }).distinctBy { it.mediaType to it.id }.take(12)
            coroutineScope {
                launch {
                    for (person in peopleNeedingCredits) {
                        val credits = try {
                            withTimeoutOrNull(2_000) {
                                withContext(Dispatchers.IO) { mediaRepository.getPersonDetails(person.personId).knownFor }
                            }.orEmpty()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { emptyList() }
                        val owned = inLibrary(credits)
                        if (owned.isNotEmpty()) {
                            val row = Category("person_${person.personId}", person.name, owned)
                            peopleRows = peopleRows + row
                            _uiState.update { it.copy(personResults = it.personResults + row, isLoading = false) }
                        }
                    }
                    _uiState.update { it.copy(isLoading = false) }
                }
                top.forEach { item -> launch {
                    val logo = slots.withPermit { fetchLogo(item) }
                    if (!logo.isNullOrBlank()) {
                        _uiState.update { it.copy(cardLogoUrls = it.cardLogoUrls + ("${item.mediaType}_${item.id}" to logo)) }
                    }
                } }
            }
        }
    }

    private fun debounceSearch() { searchJob?.cancel(); searchJob = viewModelScope.launch { delay(260); search() } }

    fun clearSearch() {
        searchJob?.cancel()
        activeSearchQuery = null
        completedQuery = null
        _uiState.value = _uiState.value.copy(query = "", isLoading = false, results = EMPTY_MEDIA_ITEMS,
            movieResults = EMPTY_MEDIA_ITEMS, tvResults = EMPTY_MEDIA_ITEMS, personResults = EMPTY_CATEGORIES,
            cardLogoUrls = EMPTY_LOGO_URLS, error = null)
    }
}
