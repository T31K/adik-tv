package com.arflix.tv.ui.screens.home

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.arflix.tv.R
import com.arflix.tv.data.model.CatalogSourceType
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.NextEpisode
import com.arflix.tv.data.repository.CatalogRepository
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.PlaybackProgress
import com.arflix.tv.data.repository.PlaybackProgressStore
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.megaflix.MegaflixDownloadManager
import com.arflix.tv.megaflix.MegaflixLibraryBuilder
import com.arflix.tv.megaflix.MegaflixSyncManager
import com.arflix.tv.updater.ApkDownloader
import com.arflix.tv.updater.ApkInstaller
import com.arflix.tv.updater.AppUpdateRepository
import com.arflix.tv.updater.UpdatePreferences
import com.arflix.tv.updater.UpdateStatus
import com.arflix.tv.updater.UpdateStatusManager
import com.arflix.tv.updater.VersionUtils
import com.arflix.tv.util.AppLogger
import com.arflix.tv.util.Constants
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class HomeUiState(
    val isLoading: Boolean = false,
    val isInitialLoad: Boolean = true,
    val categories: List<Category> = emptyList(),
    val error: String? = null,
    val heroItem: MediaItem? = null,
    val heroLogoUrl: String? = null,
    val heroOverviewOverride: String? = null,
    // Home hero metadata visibility toggle (issue #72)
    val showBudget: Boolean = true,
    val clockFormat: String = "24h",
    val smoothScrolling: Boolean = false,
    // Toast
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.INFO,
    // App updates
    val updateStatus: UpdateStatus = UpdateStatus.Idle,
    val showAppUpdateDialog: Boolean = false,
    val hasUpdateBadge: Boolean = false,
    val categoryHasMoreMap: Map<String, Boolean> = emptyMap()
)

internal fun compactHomeCategoriesForCache(
    categories: List<Category>,
    maxItemsPerCategory: Int
): List<Category> = categories.mapNotNull { category ->
    val items = category.items
        .asSequence()
        .filter { item -> !item.isPlaceholder && item.title.isNotBlank() && item.title != "Unknown" }
        .take(maxItemsPerCategory)
        .toList()
    category.copy(items = items).takeIf { items.isNotEmpty() }
}

enum class ToastType {
    SUCCESS, ERROR, INFO
}

/**
 * Home: feed category rows (local library), a Continue Watching row built from
 * [PlaybackProgressStore], the static hero, the Megaflix sync/heal loop and the
 * self-update flow.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val catalogRepository: CatalogRepository,
    private val profileManager: ProfileManager,
    private val playbackProgressStore: PlaybackProgressStore,
    private val megaflixLibrary: MegaflixLibraryBuilder,
    private val megaflixSyncManager: MegaflixSyncManager,
    private val megaflixDownloadManager: MegaflixDownloadManager,
    private val appUpdateRepository: AppUpdateRepository,
    private val apkDownloader: ApkDownloader,
    private val updatePreferences: UpdatePreferences,
    private val updateStatusManager: UpdateStatusManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Per-card clearlogo URLs keyed "${mediaType}_$id"; a snapshot map so one arrival recomposes one card. */
    val cardLogoUrls = mutableStateMapOf<String, String>()

    private val isLowRamDevice = runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }.getOrDefault(false)
    private val rowPageSize = if (isLowRamDevice) 20 else 30
    private val maxRowItems = 200
    private val homeCacheItemsPerCategory = if (isLowRamDevice) 12 else 16

    private val gson = com.google.gson.Gson()

    // ── Rows ──
    /** Every loaded feed row with all of its items; [shownCounts] pages them into the UI. */
    private var fullRows: List<Category> = emptyList()
    private val shownCounts = HashMap<String, Int>()
    /** Library items by "${mediaType}_$id", used to give Continue Watching cards their artwork. */
    private var libraryLookup: Map<String, MediaItem> = emptyMap()
    private var latestProgress: List<PlaybackProgress> = emptyList()
    private var loadHomeJob: Job? = null
    private var homeDataLoadAttempted = false
    private var lastHomeDataLoadAtMs = 0L

    // ── Hero ──
    private var heroJob: Job? = null
    private val heroDetailsCache = ConcurrentHashMap<String, MediaItem>()

    // ── Logos ──
    private val logoCachePrefs = context.getSharedPreferences("logo_cache", Context.MODE_PRIVATE)
    private val logoFetchInFlight = Collections.synchronizedSet(mutableSetOf<String>())
    private var logoCacheDiskWriteJob: Job? = null
    private var focusPrefetchJob: Job? = null
    private val preloadedImageRequests = Collections.synchronizedSet(mutableSetOf<String>())
    private val density = context.resources.displayMetrics.density
    private val logoPreloadWidth = (220 * density).toInt().coerceAtLeast(1)
    private val logoPreloadHeight = (64 * density).toInt().coerceAtLeast(1)
    private val backdropPreloadWidth = (210 * density).toInt().coerceAtLeast(1)
    private val backdropPreloadHeight = (backdropPreloadWidth / (16f / 9f)).toInt().coerceAtLeast(1)

    private var activeProfileId: String? = null
    private var observedContentLanguage: String? = null
    private var usedPreloadedData = false
    private var downloadJob: Job? = null

    init {
        startMegaflixSync()

        viewModelScope.launch(Dispatchers.IO) {
            val restored = restoreLogoCacheFromDisk()
            if (restored.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    restored.forEach { (key, url) -> if (!cardLogoUrls.containsKey(key)) cardLogoUrls[key] = url }
                }
            }
        }

        // Instant Home: last session's rows from disk while the feed sync runs.
        viewModelScope.launch(Dispatchers.IO) {
            val cached = loadRowsCache()
            if (cached.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    if (fullRows.isEmpty()) {
                        fullRows = cached
                        publish()
                        _uiState.value = _uiState.value.copy(isLoading = false, isInitialLoad = false, error = null)
                    }
                }
            }
        }

        viewModelScope.launch {
            playbackProgressStore.observeAll().collect { entries ->
                latestProgress = entries
                publish()
            }
        }

        viewModelScope.launch {
            profileManager.activeProfileId
                .distinctUntilChanged()
                .collect { profileId ->
                    val previous = activeProfileId
                    activeProfileId = profileId
                    if (previous != null && previous != profileId) {
                        heroJob?.cancel()
                        _uiState.value = _uiState.value.copy(heroItem = null, heroLogoUrl = null, heroOverviewOverride = null)
                        loadHomeData()
                    }
                }
        }

        viewModelScope.launch {
            try {
                observeHomeProfilePreferences(
                    activeProfileId = profileManager.activeProfileId,
                    preferences = context.settingsDataStore.data
                ).collect { preferences ->
                    mediaRepository.contentLanguage = preferences.contentLanguage
                    val normalizedLanguage = mediaRepository.contentLanguage
                    val langChanged = observedContentLanguage?.let { it != normalizedLanguage } ?: false
                    observedContentLanguage = normalizedLanguage
                    _uiState.value = _uiState.value.copy(
                        showBudget = preferences.showBudget,
                        clockFormat = preferences.clockFormat,
                        smoothScrolling = preferences.smoothScrolling
                    )
                    if (langChanged) {
                        heroJob?.cancel()
                        heroDetailsCache.clear()
                        mediaRepository.clearMediaCache()
                        _uiState.value = _uiState.value.copy(heroOverviewOverride = null)
                        loadHomeData()
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e("HomeVM", "Failed to observe profile preferences: ${e.message}", e)
            }
        }

        viewModelScope.launch {
            var previousStatus: UpdateStatus = UpdateStatus.Idle
            updateStatusManager.status.collect { status ->
                val hasBadge = status is UpdateStatus.UpdateAvailable ||
                    status is UpdateStatus.ReadyToInstall ||
                    status is UpdateStatus.Downloading
                var isIgnored = false
                if (status is UpdateStatus.UpdateAvailable) {
                    val persistedIgnoredTag = updatePreferences.ignoredTag.first()
                    if (persistedIgnoredTag == status.update.tag || updateStatusManager.sessionIgnoredTag == status.update.tag) {
                        isIgnored = true
                    }
                }
                // Auto-open only when we first discover a new unignored update.
                val shouldAutoOpen = status is UpdateStatus.UpdateAvailable &&
                    previousStatus !is UpdateStatus.UpdateAvailable && !isIgnored
                _uiState.value = _uiState.value.copy(
                    updateStatus = status,
                    showAppUpdateDialog = if (shouldAutoOpen) true else _uiState.value.showAppUpdateDialog,
                    hasUpdateBadge = hasBadge && !isIgnored
                )
                previousStatus = status
            }
        }

        // Check for updates shortly after startup.
        viewModelScope.launch {
            delay(if (isLowRamDevice) 15_000L else 10_000L)
            checkForAppUpdates(silent = true)
        }
    }

    // Megaflix: sync the content feed on launch, then poll /rev every 60s while alive.
    private fun startMegaflixSync() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { megaflixSyncManager.sync() }
            loadHomeData()
            // After Home is up: scan new READY movies for missing pieces; broken ones
            // are deleted + re-queued, so refresh Home to drop them.
            if (runCatching { megaflixDownloadManager.verifyReadyFiles() }.getOrDefault(0) > 0) {
                loadHomeData()
            }
            while (true) {
                delay(60_000)
                // Heal stalled downloads: swap a crawling torrent to its next
                // ranked magnet, or kick the drain if it's idle with work pending.
                runCatching { megaflixDownloadManager.pollAndHeal() }
                if (runCatching { megaflixSyncManager.revChanged() }.getOrDefault(false)) {
                    runCatching { megaflixSyncManager.sync() }
                    loadHomeData()
                }
            }
        }
    }

    // ───────────────────────────── Rows ─────────────────────────────

    private fun loadHomeData() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            loadHomeJob?.cancel()
            homeDataLoadAttempted = true
            if (fullRows.isEmpty()) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }
            loadHomeJob = viewModelScope.launch {
                try {
                    val (rows, lookup) = withContext(Dispatchers.IO) { buildRows() }
                    fullRows = rows
                    libraryLookup = lookup
                    shownCounts.keys.retainAll(rows.map { it.id }.toSet())
                    lastHomeDataLoadAtMs = SystemClock.elapsedRealtime()
                    publish()
                    _uiState.value = _uiState.value.copy(isLoading = false, isInitialLoad = false, error = null)
                    launch(Dispatchers.IO) { persistRowsCache(rows) }
                    warmFirstRows(rows)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    AppLogger.e("HomeVM", "Home load failed: ${e.message}", e)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isInitialLoad = false,
                        error = if (fullRows.isEmpty()) {
                            e.message ?: context.getString(R.string.home_please_check_connection)
                        } else null
                    )
                }
            }
        }
    }

    /** Feed rows (CatalogRepository's server-driven list) filled from the local library. */
    private suspend fun buildRows(): Pair<List<Category>, Map<String, MediaItem>> = coroutineScope {
        val catalogs = catalogRepository.getCatalogs()
            .filter { it.sourceType == CatalogSourceType.LOCAL }
            .distinctBy { it.id }
        val rows = catalogs.map { cfg ->
            async {
                try {
                    mediaRepository.loadLocalCatalog(cfg, maxRowItems)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            }
        }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
        rows.forEach { row -> row.items.forEach { mediaRepository.cacheItem(it) } }

        val library = buildList {
            rows.forEach { addAll(it.items) }
            MediaType.entries.forEach { type ->
                try {
                    addAll(megaflixLibrary.libraryItems(type))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
        rows to library.associateBy { mediaKey(it.mediaType, it.id) }
    }

    /** Pushes rows (paged) + Continue Watching into the UI state. Main thread. */
    private fun publish() {
        val watchedMovies = latestProgress
            .filter { it.watched && it.mediaType == MediaType.MOVIE }
            .mapTo(HashSet()) { it.tmdbId }
        val baseRows = fullRows.map { row ->
            val shown = shownCount(row.id)
            val paged = if (row.items.size > shown) row.copy(items = row.items.take(shown)) else row
            if (paged.items.none { it.isWatched || (it.mediaType == MediaType.MOVIE && it.id in watchedMovies) }) {
                paged
            } else {
                paged.copy(items = paged.items.map { item ->
                    val watched = item.mediaType == MediaType.MOVIE && item.id in watchedMovies
                    if (item.isWatched == watched) item else item.copy(isWatched = watched)
                })
            }
        }
        val continueWatching = buildResumeCards()
        val categories = if (continueWatching.isEmpty()) {
            baseRows
        } else {
            listOf(Category(CONTINUE_WATCHING_ID, "Continue Watching", continueWatching)) + baseRows
        }
        _uiState.value = _uiState.value.copy(
            categories = categories,
            categoryHasMoreMap = fullRows.associate { it.id to (it.items.size > shownCount(it.id)) }
        )
        if (_uiState.value.heroItem == null) {
            chooseInitialHero(categories)?.let { updateHeroItem(it) }
        }
    }

    private fun shownCount(categoryId: String): Int = shownCounts[categoryId] ?: rowPageSize

    /** Resume points joined with library items (poster/backdrop), newest first. */
    private fun buildResumeCards(): List<MediaItem> {
        if (latestProgress.isEmpty()) return emptyList()
        return playbackProgressStore.continueWatchingOf(latestProgress).mapNotNull { progress ->
            val base = libraryLookup[mediaKey(progress.mediaType, progress.tmdbId)]
                ?: mediaRepository.getCachedFullItem(progress.mediaType, progress.tmdbId)
                ?: return@mapNotNull null
            progress.toResumeCard(base)
        }
    }

    private fun PlaybackProgress.toResumeCard(base: MediaItem): MediaItem {
        val remainingSeconds = ((durationMs - positionMs).coerceAtLeast(0L)) / 1000L
        val resumeClock = formatResumeClock(positionMs / 1000L)
        val isEpisode = mediaType == MediaType.TV && season != null && episode != null
        val subtitle = if (isEpisode) {
            context.getString(R.string.continue_season_episode, season, episode)
        } else {
            context.getString(R.string.continue_from, resumeClock)
        }
        return base.copy(
            title = base.title.ifBlank { title },
            subtitle = subtitle,
            progress = percent,
            isWatched = false,
            showPlaybackProgress = percent in 1 until Constants.WATCHED_THRESHOLD,
            timeRemainingLabel = formatTimeRemainingCompact(remainingSeconds),
            nextEpisode = if (isEpisode) {
                NextEpisode(id = 0, seasonNumber = season!!, episodeNumber = episode!!, name = "Episode $episode")
            } else null,
            totalEpisodes = null,
            watchedEpisodes = null,
            episodeStill = null
        )
    }

    private fun chooseInitialHero(categories: List<Category>): MediaItem? =
        categories.firstOrNull { it.id != CONTINUE_WATCHING_ID && it.items.any(::isActionable) }
            ?.items?.firstOrNull(::isActionable)
            ?: categories.firstOrNull()?.items?.firstOrNull(::isActionable)

    fun loadNextHomeRowPage(categoryId: String) {
        val row = fullRows.firstOrNull { it.id == categoryId } ?: return
        val shown = shownCount(categoryId)
        if (row.items.size <= shown) return
        shownCounts[categoryId] = (shown + rowPageSize).coerceAtMost(row.items.size)
        publish()
    }

    fun maybeLoadNextPageForCategory(categoryId: String, focusedItemIndex: Int) {
        if (focusedItemIndex >= shownCount(categoryId) - NEAR_END_THRESHOLD) loadNextHomeRowPage(categoryId)
    }

    fun onMobileCategoryVisiblePosition(categoryId: String, lastVisibleItemIndex: Int) {
        maybeLoadNextPageForCategory(categoryId, lastVisibleItemIndex)
        val items = _uiState.value.categories.firstOrNull { it.id == categoryId }?.items.orEmpty()
        prefetchLogos(items.drop(lastVisibleItemIndex.coerceAtLeast(0)).take(4))
    }

    fun onViewAllVisiblePosition(categoryId: String, lastVisibleItemIndex: Int, lookAheadItems: Int) {
        if (lastVisibleItemIndex + lookAheadItems >= shownCount(categoryId)) loadNextHomeRowPage(categoryId)
        val items = _uiState.value.categories.firstOrNull { it.id == categoryId }?.items.orEmpty()
        val start = (lastVisibleItemIndex - lookAheadItems).coerceAtLeast(0)
        prefetchLogos(items.drop(start).take(lookAheadItems * 2))
    }

    /** Re-load the rows only once they are stale; a failed first load retries on every resume. */
    fun refreshHomeDataIfStale() {
        if (!homeDataLoadAttempted) return
        if (loadHomeJob?.isActive == true) return
        val lastLoadAtMs = lastHomeDataLoadAtMs
        if (lastLoadAtMs == 0L || SystemClock.elapsedRealtime() - lastLoadAtMs >= HOME_DATA_STALE_AFTER_MS) {
            loadHomeData()
        }
    }

    fun refresh() {
        loadHomeData()
    }

    /**
     * Startup preload handoff: only used when Home has nothing to show yet.
     */
    fun setPreloadedData(
        categories: List<Category>,
        heroItem: MediaItem?,
        heroLogoUrl: String?,
        logoCache: Map<String, String>
    ) {
        logoCache.forEach { (key, url) -> if (url.isNotBlank() && !cardLogoUrls.containsKey(key)) cardLogoUrls[key] = url }
        if (usedPreloadedData || categories.isEmpty() || fullRows.isNotEmpty()) return
        usedPreloadedData = true
        fullRows = categories.filter { it.id != CONTINUE_WATCHING_ID && it.items.isNotEmpty() }
        fullRows.forEach { row -> row.items.forEach { mediaRepository.cacheItem(it) } }
        publish()
        _uiState.value = _uiState.value.copy(isLoading = false, isInitialLoad = false, error = null)
        if (heroItem != null && isActionable(heroItem)) {
            _uiState.value = _uiState.value.copy(heroItem = heroItem, heroLogoUrl = heroLogoUrl)
        }
    }

    // ───────────────────────────── Hero ─────────────────────────────

    fun updateHeroItem(item: MediaItem) {
        if (!isActionable(item)) return
        val key = mediaKey(item.mediaType, item.id)
        val details = heroDetailsCache[key] ?: mediaRepository.getCachedFullItem(item.mediaType, item.id)
        val hero = details?.let { item.withHeroDetails(it) } ?: item
        val current = _uiState.value
        _uiState.value = current.copy(
            heroItem = hero,
            heroLogoUrl = cardLogoUrls[key],
            heroOverviewOverride = details?.overview?.takeIf { it.isNotBlank() }
        )
        heroJob?.cancel()
        if (cardLogoUrls.containsKey(key) && heroDetailsCache.containsKey(key)) return
        heroJob = viewModelScope.launch {
            delay(HERO_DEBOUNCE_MS)
            if (!cardLogoUrls.containsKey(key)) {
                val logo = fetchLogo(item)
                if (logo != null && isCurrentHero(item)) {
                    _uiState.value = _uiState.value.copy(heroLogoUrl = logo)
                }
            }
            if (!heroDetailsCache.containsKey(key)) {
                val full = withContext(Dispatchers.IO) {
                    try {
                        if (item.mediaType == MediaType.MOVIE) {
                            mediaRepository.getMovieDetails(item.id)
                        } else {
                            mediaRepository.getTvDetails(item.id)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                } ?: return@launch
                heroDetailsCache[key] = full
                val currentHero = _uiState.value.heroItem
                if (currentHero != null && isCurrentHero(item)) {
                    val updated = currentHero.withHeroDetails(full)
                    mediaRepository.cacheItem(updated)
                    _uiState.value = _uiState.value.copy(
                        heroItem = updated,
                        heroOverviewOverride = full.overview.takeIf { it.isNotBlank() }
                    )
                }
            }
        }
    }

    private fun isCurrentHero(item: MediaItem): Boolean {
        val hero = _uiState.value.heroItem ?: return false
        return hero.id == item.id && hero.mediaType == item.mediaType
    }

    private fun MediaItem.withHeroDetails(details: MediaItem): MediaItem = copy(
        duration = details.duration.ifEmpty { duration },
        releaseDate = details.releaseDate ?: releaseDate,
        imdbRating = details.imdbRating.ifEmpty { imdbRating },
        tmdbRating = details.tmdbRating.ifEmpty { tmdbRating },
        budget = details.budget ?: budget,
        overview = details.overview.ifBlank { overview },
        genreIds = genreIds.ifEmpty { details.genreIds }
    )

    // ───────────────────────────── Logos / prefetch ─────────────────────────────

    fun onFocusChanged(rowIndex: Int, itemIndex: Int, shouldPrefetch: Boolean = true) {
        focusPrefetchJob?.cancel()
        if (!shouldPrefetch) return
        focusPrefetchJob = viewModelScope.launch {
            delay(FOCUS_PREFETCH_COALESCE_MS)
            val items = _uiState.value.categories.getOrNull(rowIndex)?.items.orEmpty()
            if (items.isEmpty()) return@launch
            val start = itemIndex.coerceIn(0, items.lastIndex)
            val window = items.subList(start, minOf(start + 5, items.size)).filter(::isActionable)
            prefetchLogos(window)
            preloadImages(window.mapNotNull { it.backdrop ?: it.image.takeIf(String::isNotBlank) }, backdropPreloadWidth, backdropPreloadHeight)
        }
    }

    fun preloadLogosForCategory(categoryIndex: Int, prioritizeVisible: Boolean = false) {
        val items = _uiState.value.categories.getOrNull(categoryIndex)?.items.orEmpty()
        prefetchLogos(items.take(if (prioritizeVisible) 7 else 5))
    }

    private fun warmFirstRows(rows: List<Category>) {
        rows.take(2).forEach { prefetchLogos(it.items.take(6)) }
    }

    private fun prefetchLogos(items: List<MediaItem>) {
        val missing = items.filter { isActionable(it) && !cardLogoUrls.containsKey(mediaKey(it.mediaType, it.id)) }
        if (missing.isEmpty()) return
        viewModelScope.launch { missing.forEach { fetchLogo(it) } }
    }

    /** Fetches (once) and publishes one clearlogo. Main-thread caller. */
    private suspend fun fetchLogo(item: MediaItem): String? {
        val key = mediaKey(item.mediaType, item.id)
        cardLogoUrls[key]?.let { return it }
        if (!logoFetchInFlight.add(key)) return null
        val url = try {
            withContext(Dispatchers.IO) { mediaRepository.getLogoUrl(item.mediaType, item.id) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } finally {
            logoFetchInFlight.remove(key)
        } ?: return null
        cardLogoUrls[key] = url
        mediaRepository.cacheLogoUrl(item.mediaType, item.id, url)
        preloadImages(listOf(url), logoPreloadWidth, logoPreloadHeight)
        saveLogoCacheToDisk()
        return url
    }

    private fun preloadImages(urls: List<String>, width: Int, height: Int) {
        urls.filter { preloadedImageRequests.add("$it|${width}x$height") }
            .take(if (isLowRamDevice) 4 else 8)
            .forEach { url ->
                val cacheKey = "$url|${width}x$height"
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(width, height)
                        .precision(Precision.INEXACT)
                        .allowHardware(true)
                        .memoryCacheKey(cacheKey)
                        .placeholderMemoryCacheKey(cacheKey)
                        .build()
                )
            }
    }

    private fun restoreLogoCacheFromDisk(): Map<String, String> = try {
        val json = logoCachePrefs.getString("urls", null)
        if (json == null || json.length > MAX_LOGO_CACHE_JSON_CHARS) {
            if (json != null) logoCachePrefs.edit().remove("urls").apply()
            emptyMap()
        } else {
            val obj = org.json.JSONObject(json)
            buildMap {
                obj.keys().forEach { key ->
                    val url = obj.optString(key)
                    if (url.isNotBlank()) {
                        put(key, url)
                        val parts = key.split("_")
                        val type = if (parts.getOrNull(0).equals("tv", ignoreCase = true)) MediaType.TV else MediaType.MOVIE
                        parts.getOrNull(1)?.toIntOrNull()?.let { mediaRepository.cacheLogoUrl(type, it, url) }
                    }
                }
            }
        }
    } catch (e: Exception) {
        runCatching { logoCachePrefs.edit().remove("urls").apply() }
        emptyMap()
    }

    private fun saveLogoCacheToDisk() {
        logoCacheDiskWriteJob?.cancel()
        logoCacheDiskWriteJob = viewModelScope.launch {
            delay(2_000L) // debounce
            val snapshot = HashMap(cardLogoUrls).entries.toList().takeLast(MAX_LOGO_CACHE_ENTRIES)
            withContext(Dispatchers.IO) {
                runCatching {
                    val json = org.json.JSONObject(snapshot.associate { it.key to it.value }).toString()
                    logoCachePrefs.edit().putString("urls", json).apply()
                }
            }
        }
    }

    // ───────────────────────────── Rows disk cache ─────────────────────────────

    private fun rowsCacheFile(): java.io.File {
        val profileId = profileManager.getProfileIdSync().ifBlank { "default" }
        return java.io.File(context.cacheDir, "adik_home_rows_v1_$profileId.json")
    }

    private fun persistRowsCache(rows: List<Category>) {
        runCatching {
            val target = rowsCacheFile()
            val temporary = java.io.File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(gson.toJson(compactHomeCategoriesForCache(rows, homeCacheItemsPerCategory)))
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
        }
    }

    private fun loadRowsCache(): List<Category> = runCatching {
        val file = rowsCacheFile()
        if (!file.exists() || file.length() > MAX_ROWS_CACHE_BYTES) return@runCatching emptyList()
        val type = object : com.google.gson.reflect.TypeToken<List<Category>>() {}.type
        val parsed: List<Category> = gson.fromJson(file.readText(), type) ?: emptyList()
        // Gson bypasses Kotlin defaults: drop anything malformed.
        @Suppress("SENSELESS_COMPARISON")
        parsed.filter { it.id != null && it.items != null }
            .map { row -> row.copy(items = row.items.filter { it != null && it.title.isNotBlank() && it.mediaType != null }) }
            .filter { it.items.isNotEmpty() }
    }.getOrDefault(emptyList())

    // ───────────────────────────── Actions ─────────────────────────────

    fun cacheItem(item: MediaItem) {
        mediaRepository.cacheItem(item)
    }

    fun cacheLogoUrl(mediaType: MediaType, mediaId: Int, logoUrl: String) {
        mediaRepository.cacheLogoUrl(mediaType, mediaId, logoUrl)
    }

    fun toggleWatched(item: MediaItem) {
        viewModelScope.launch {
            try {
                if (item.mediaType == MediaType.MOVIE) {
                    val watched = !item.isWatched
                    playbackProgressStore.setWatched(item.id, MediaType.MOVIE, null, null, watched, item.title)
                    showToast(
                        context.getString(if (watched) R.string.details_marked_watched else R.string.details_marked_unwatched),
                        ToastType.SUCCESS
                    )
                } else {
                    val nextEp = item.nextEpisode
                    if (nextEp == null) {
                        showToast(context.getString(R.string.home_no_episode_info), ToastType.ERROR)
                        return@launch
                    }
                    playbackProgressStore.setWatched(
                        item.id, MediaType.TV, nextEp.seasonNumber, nextEp.episodeNumber, true, item.title
                    )
                    showToast(
                        context.getString(R.string.details_episode_marked_watched, nextEp.seasonNumber, nextEp.episodeNumber),
                        ToastType.SUCCESS
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showToast(context.getString(R.string.details_failed_update_watched), ToastType.ERROR)
            }
        }
    }

    fun removeFromContinueWatching(item: MediaItem) {
        val season = item.nextEpisode?.seasonNumber.takeIf { item.mediaType == MediaType.TV }
        val episode = item.nextEpisode?.episodeNumber.takeIf { item.mediaType == MediaType.TV }
        // Optimistic: drop the card now; the progress flow republishes the row after the write.
        _uiState.value = _uiState.value.copy(
            categories = ContinueWatchingRowReducer.remove(_uiState.value.categories, item.mediaType, item.id, season, episode)
        )
        viewModelScope.launch {
            try {
                playbackProgressStore.remove(item.id, item.mediaType, season, episode)
                showToast(context.getString(R.string.home_removed_continue_watching), ToastType.SUCCESS)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                publish()
                showToast(context.getString(R.string.home_failed_remove_continue_watching), ToastType.ERROR)
            }
        }
    }

    fun dismissToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    private fun showToast(message: String, type: ToastType) {
        _uiState.value = _uiState.value.copy(toastMessage = message, toastType = type)
    }

    // ───────────────────────────── App updates ─────────────────────────────

    fun checkForAppUpdates(silent: Boolean = false) {
        if (!appUpdateRepository.supportsSelfUpdate()) return
        viewModelScope.launch {
            if (!silent) updateStatusManager.updateStatus(UpdateStatus.Checking)
            appUpdateRepository.getLatestUpdate()
                .onSuccess { update ->
                    val localVer = appUpdateRepository.getInstalledVersionName()
                    if (VersionUtils.isRemoteNewer(update.tag, localVer)) {
                        updateStatusManager.updateStatus(UpdateStatus.UpdateAvailable(update))
                    } else {
                        if (!silent) showToast(context.getString(R.string.update_already_latest), ToastType.INFO)
                        updateStatusManager.reset()
                    }
                }
                .onFailure { error ->
                    if (!silent) {
                        showToast(error.message ?: context.getString(R.string.update_check_failed), ToastType.ERROR)
                    }
                    updateStatusManager.reset()
                }
        }
    }

    fun downloadAppUpdate() {
        val update = when (val currentStatus = updateStatusManager.status.value) {
            is UpdateStatus.UpdateAvailable -> currentStatus.update
            is UpdateStatus.Failure -> currentStatus.update
            else -> return
        } ?: return
        if (!appUpdateRepository.supportsSelfUpdate()) return

        downloadJob = viewModelScope.launch {
            updateStatusManager.updateStatus(UpdateStatus.Downloading(0f, update))
            val safeName = update.assetName.replace(FILE_NAME_REGEX, "_")
            val dest = java.io.File(java.io.File(context.cacheDir, "updates"), safeName)
            val result = withContext(Dispatchers.IO) {
                apkDownloader.download(update.assetUrl, dest) { downloaded, total ->
                    val progress = if (total != null && total > 0L) {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else null
                    updateStatusManager.updateStatus(UpdateStatus.Downloading(progress, update))
                }
            }
            result.onSuccess { file ->
                updateStatusManager.updateStatus(UpdateStatus.ReadyToInstall(file.absolutePath, update))
                // Automatically prompt install once downloaded.
                installAppUpdateOrRequestPermission()
            }.onFailure { error ->
                updateStatusManager.updateStatus(
                    UpdateStatus.Failure(error.message ?: context.getString(R.string.update_download_failed), update)
                )
            }
        }
    }

    fun cancelDownloadAppUpdate() {
        downloadJob?.cancel()
        downloadJob = null
        val currentStatus = updateStatusManager.status.value
        if (currentStatus is UpdateStatus.Downloading) {
            updateStatusManager.updateStatus(UpdateStatus.UpdateAvailable(currentStatus.update))
        }
    }

    fun installAppUpdateOrRequestPermission() {
        val currentStatus = updateStatusManager.status.value as? UpdateStatus.ReadyToInstall ?: return
        val update = currentStatus.update
        val apkFile = java.io.File(currentStatus.apkPath)
        if (!apkFile.exists()) {
            updateStatusManager.updateStatus(UpdateStatus.Failure("Downloaded file is missing", update))
            return
        }
        val conflictMsg = ApkInstaller.checkSignatureConflict(context, apkFile)
        if (conflictMsg != null) {
            updateStatusManager.updateStatus(UpdateStatus.Failure(conflictMsg, update))
            return
        }
        ApkInstaller.launchInstall(context, apkFile)
        updateStatusManager.updateStatus(UpdateStatus.Installing(update))
        // Mark this release as ignored so it doesn't pop up again if the user cancels the install.
        viewModelScope.launch { updatePreferences.setIgnoredTag(update.tag) }
    }

    fun dismissAppUpdateDialog() {
        // Keep updateStatusManager as-is so the badge stays active.
        _uiState.value = _uiState.value.copy(showAppUpdateDialog = false)
    }

    fun ignoreAppUpdate() {
        val currentStatus = updateStatusManager.status.value
        if (currentStatus is UpdateStatus.UpdateAvailable) {
            updateStatusManager.sessionIgnoredTag = currentStatus.update.tag
            viewModelScope.launch { updatePreferences.setIgnoredTag(currentStatus.update.tag) }
        }
        _uiState.value = _uiState.value.copy(showAppUpdateDialog = false, hasUpdateBadge = false)
        updateStatusManager.reset()
    }

    private fun isActionable(item: MediaItem): Boolean = item.id > 0 && !item.isPlaceholder

    private fun mediaKey(type: MediaType, id: Int): String = "${type}_$id"

    companion object {
        const val CONTINUE_WATCHING_ID = "continue_watching"
        private const val HOME_DATA_STALE_AFTER_MS = 6 * 60 * 60 * 1000L
        private const val HERO_DEBOUNCE_MS = 80L
        private const val FOCUS_PREFETCH_COALESCE_MS = 120L
        private const val NEAR_END_THRESHOLD = 4
        private const val MAX_LOGO_CACHE_ENTRIES = 420
        private const val MAX_LOGO_CACHE_JSON_CHARS = 500_000
        private const val MAX_ROWS_CACHE_BYTES = 8_000_000L
        private val FILE_NAME_REGEX = Regex("[^a-zA-Z0-9._-]")
    }
}

private fun formatResumeClock(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** "45min left", "1hr 15min left", "2hr left"; null under a minute. */
private fun formatTimeRemainingCompact(totalSeconds: Long): String? {
    val safe = totalSeconds.coerceAtLeast(0L)
    if (safe < 60) return null
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}hr ${minutes}min left"
        hours > 0 -> "${hours}hr left"
        else -> "${minutes}min left"
    }
}
