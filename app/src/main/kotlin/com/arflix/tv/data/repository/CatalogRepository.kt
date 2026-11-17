package com.arflix.tv.data.repository

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.R
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogPackManifest
import com.arflix.tv.data.model.CatalogSourceType
import com.arflix.tv.megaflix.DownloadStateStore
import com.arflix.tv.megaflix.MegaflixFeedApi
import com.arflix.tv.util.AppLogger
import com.arflix.tv.util.Constants
import com.arflix.tv.util.settingsDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Failure of a catalog operation, carrying the string resource instead of a
 * finished message.
 *
 * The repository only has the application context, whose resources follow the
 * system language rather than the language selected in the app, so the text is
 * resolved at the @Composable display point (see SettingsScreen).
 */
class CatalogException(
    @param:StringRes val messageRes: Int,
    val formatArgs: List<Any> = emptyList()
) : Exception()

/**
 * Home rows. They come from the feed (`/rows` on MegaflixFeedApi) so they can be changed
 * server-side without an app update; [defaultRows] is the fallback when the endpoint is
 * unreachable. MediaRepository.loadLocalCatalog maps each row id to a library filter.
 *
 * Per profile the user can reorder, rename and hide rows. The saved arrangement
 * (`profile_<id>_catalogs_v1`) only stores order + titles of feed rows; rows the feed adds
 * later slot in at their feed position, rows the feed drops disappear.
 */
@Singleton
class CatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
    private val megaflixFeedApi: MegaflixFeedApi,
    private val megaflixStore: DownloadStateStore
) {
    private val gson = Gson()
    private val listType = TypeToken.getParameterized(List::class.java, CatalogConfig::class.java).type
    private val stringListType = TypeToken.getParameterized(List::class.java, String::class.java).type

    private fun catalogsKey(profileId: String) = stringPreferencesKey("profile_${profileId}_catalogs_v1")
    private fun hiddenKey(profileId: String) = stringPreferencesKey("profile_${profileId}_hidden_preinstalled_catalogs_v2")

    private val feedMutex = Mutex()
    @Volatile private var cachedFeedRows: List<CatalogConfig>? = null

    private val defaultRows = listOf(
        row("cat_new", "New Releases"),
        row("cat_g_878", "Sci-Fi"),
        row("cat_g_28", "Action"),
        row("cat_g_12", "Adventure"),
        row("cat_g_16", "Animation"),
        row("cat_g_10751", "Family"),
        row("cat_g_35", "Comedy"),
        row("cat_g_18", "Drama"),
        row("cat_g_53", "Thriller"),
        row("cat_all", "All Movies"),
    )

    private fun row(id: String, title: String) =
        CatalogConfig(id = id, title = title, sourceType = CatalogSourceType.LOCAL, isPreinstalled = false)

    /** Feed rows, fetched once per process; defaults if the endpoint fails or is empty. */
    private suspend fun feedRows(): List<CatalogConfig> {
        cachedFeedRows?.let { return it }
        return feedMutex.withLock {
            cachedFeedRows?.let { return@withLock it }
            val fetched = runCatching {
                megaflixFeedApi.getRows(Constants.MEGAFLIX_FEED_TOKEN, megaflixStore.getDeviceId()).rows
                    .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                    .map { row(it.id, it.title) }
                    .distinctBy { it.id }
            }.onFailure {
                AppLogger.w("CatalogRepository", "rows endpoint failed, using default rows: ${it.message}")
            }.getOrNull()?.takeIf { it.isNotEmpty() }
            (fetched ?: defaultRows).also { cachedFeedRows = it }
        }
    }

    private suspend fun activeProfileId(): String {
        return profileManager.getProfileIdSync()
            .ifBlank { profileManager.getProfileId() }
            .ifBlank { "default" }
    }

    private fun safeId(profileId: String) = profileId.trim().ifBlank { "default" }

    private fun decodeSaved(profileId: String, prefs: Preferences): List<CatalogConfig> {
        val raw = prefs[catalogsKey(profileId)]
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<CatalogConfig>>(raw, listType).orEmpty() }
            .getOrElse {
                AppLogger.e("CatalogRepository", "Unreadable saved rows, ignoring", it)
                emptyList()
            }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
    }

    private fun decodeHidden(profileId: String, prefs: Preferences): Set<String> {
        val raw = prefs[hiddenKey(profileId)]
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching { gson.fromJson<List<String>>(raw, stringListType).orEmpty() }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * Applies a profile's saved order/titles to the feed rows. Feed rows that are not in the
     * saved arrangement are inserted after the last arranged row that precedes them in the
     * feed; hidden rows are dropped when [includeHidden] is false.
     */
    private fun arrange(
        feed: List<CatalogConfig>,
        saved: List<CatalogConfig>,
        hidden: Set<String>,
        includeHidden: Boolean = false
    ): List<CatalogConfig> {
        val feedById = feed.associateBy { it.id }
        val feedIndex = feed.mapIndexed { i, cfg -> cfg.id to i }.toMap()
        val result = saved.mapNotNull { s ->
            val f = feedById[s.id] ?: return@mapNotNull null
            if (s.title.isNotBlank() && s.title != f.title) f.copy(title = s.title) else f
        }.toMutableList()
        feed.filter { f -> result.none { it.id == f.id } }.forEach { missing ->
            val target = feedIndex.getValue(missing.id)
            var insertAt = 0
            result.forEachIndexed { i, cfg ->
                val idx = feedIndex[cfg.id]
                if (idx != null && idx < target) insertAt = i + 1
            }
            result.add(insertAt, missing)
        }
        return if (includeHidden) result else result.filterNot { it.id in hidden }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCatalogs(): Flow<List<CatalogConfig>> {
        return profileManager.activeProfileId
            .flatMapLatest { profileId ->
                val id = safeId(profileId)
                context.settingsDataStore.data.map { prefs ->
                    arrange(feedRows(), decodeSaved(id, prefs), decodeHidden(id, prefs))
                }
            }
            .distinctUntilChanged()
    }

    /** Visible home rows for the active profile, in the profile's order. */
    suspend fun getCatalogs(): List<CatalogConfig> = getCatalogsForProfile(activeProfileId())

    suspend fun getCatalogsForProfile(profileId: String): List<CatalogConfig> {
        val id = safeId(profileId)
        val prefs = context.settingsDataStore.data.first()
        return arrange(feedRows(), decodeSaved(id, prefs), decodeHidden(id, prefs))
    }

    /** Every row including hidden ones, in the profile's order (for move operations). */
    private suspend fun allRowsForProfile(profileId: String): List<CatalogConfig> {
        val prefs = context.settingsDataStore.data.first()
        return arrange(feedRows(), decodeSaved(profileId, prefs), decodeHidden(profileId, prefs), includeHidden = true)
    }

    suspend fun getHiddenPreinstalledCatalogIdsForActiveProfile(): List<String> =
        getHiddenPreinstalledCatalogIdsForProfile(activeProfileId())

    suspend fun getHiddenPreinstalledCatalogIdsForProfile(profileId: String): List<String> =
        decodeHidden(safeId(profileId), context.settingsDataStore.data.first()).toList()

    suspend fun setHiddenPreinstalledCatalogIdsForActiveProfile(ids: List<String>) =
        setHiddenPreinstalledCatalogIdsForProfile(activeProfileId(), ids)

    suspend fun setHiddenPreinstalledCatalogIdsForProfile(profileId: String, ids: List<String>) {
        val id = safeId(profileId)
        val cleaned = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        context.settingsDataStore.edit { prefs ->
            prefs[hiddenKey(id)] = if (cleaned.isEmpty()) "" else gson.toJson(cleaned)
        }
    }

    private suspend fun saveArrangement(profileId: String, rows: List<CatalogConfig>) {
        val compact = rows.distinctBy { it.id }.map { row(it.id, it.title) }
        context.settingsDataStore.edit { prefs ->
            prefs[catalogsKey(profileId)] = gson.toJson(compact)
        }
    }

    suspend fun replaceCatalogsForProfile(profileId: String, catalogs: List<CatalogConfig>) =
        saveArrangement(safeId(profileId), catalogs)

    suspend fun replaceCatalogsForActiveProfile(catalogs: List<CatalogConfig>) =
        saveArrangement(activeProfileId(), catalogs)

    /** Rows are feed-defined; the defaults argument is ignored. Returns the visible rows. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun ensurePreinstalledDefaults(defaultPreinstalled: List<CatalogConfig>): List<CatalogConfig> =
        getCatalogs()

    /** Removing a feed row hides it for the active profile. */
    suspend fun removeCustomCatalog(catalogId: String): Result<Unit> {
        val profileId = activeProfileId()
        if (getCatalogsForProfile(profileId).none { it.id == catalogId }) {
            return Result.failure(CatalogException(R.string.catalog_not_found))
        }
        val hidden = getHiddenPreinstalledCatalogIdsForProfile(profileId) + catalogId
        setHiddenPreinstalledCatalogIdsForProfile(profileId, hidden)
        return Result.success(Unit)
    }

    suspend fun renameCatalog(catalogId: String, newTitle: String): Boolean {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return false
        val profileId = activeProfileId()
        val rows = allRowsForProfile(profileId).toMutableList()
        val index = rows.indexOfFirst { it.id == catalogId }
        if (index < 0) return false
        rows[index] = rows[index].copy(title = trimmed)
        saveArrangement(profileId, rows)
        return true
    }

    suspend fun moveCatalogUp(catalogId: String): Boolean = moveCatalog(catalogId, -1)

    suspend fun moveCatalogDown(catalogId: String): Boolean = moveCatalog(catalogId, +1)

    /** Swaps a row with its visible neighbour; hidden rows keep their relative slots. */
    private suspend fun moveCatalog(catalogId: String, direction: Int): Boolean {
        val profileId = activeProfileId()
        val prefs = context.settingsDataStore.data.first()
        val hidden = decodeHidden(profileId, prefs)
        val rows = arrange(feedRows(), decodeSaved(profileId, prefs), hidden, includeHidden = true).toMutableList()
        val visible = rows.filterNot { it.id in hidden }
        val visibleIndex = visible.indexOfFirst { it.id == catalogId }
        val neighbourIndex = visibleIndex + direction
        if (visibleIndex < 0 || neighbourIndex !in visible.indices) return false
        val from = rows.indexOfFirst { it.id == catalogId }
        val to = rows.indexOfFirst { it.id == visible[neighbourIndex].id }
        val moved = rows[from]
        rows[from] = rows[to]
        rows[to] = moved
        saveArrangement(profileId, rows)
        return true
    }
}
