package com.arflix.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.util.Constants
import com.arflix.tv.util.playbackProgressDataStore
import com.arflix.tv.util.traktDataStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** One resume point. [season]/[episode] are null for movies. */
data class PlaybackProgress(
    val tmdbId: Int,
    val mediaType: MediaType,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val title: String = "",
    /** Sticky: set once the viewer passed [Constants.WATCHED_THRESHOLD]. */
    val watched: Boolean = false
) {
    val percent: Int
        get() = if (durationMs > 0L) ((positionMs * 100L) / durationMs).toInt().coerceIn(0, 100) else 0

    /** Worth offering "Resume" for: started for real, not finished. */
    val isResumable: Boolean
        get() = !watched && percent < Constants.WATCHED_THRESHOLD &&
            (percent >= Constants.MIN_PROGRESS_THRESHOLD || positionMs >= 60_000L)
}

/**
 * Local-only playback progress, profile-scoped (DataStore `playback_progress_prefs`).
 *
 * Replaces the inherited Trakt "local continue watching" + Supabase watch history.
 * Keyed by tmdbId (+ season/episode for series). On first use per profile it imports
 * the old `local_continue_watching_v1` entries so existing resume points survive.
 */
@Singleton
class PlaybackProgressStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val mutex = Mutex()
    private val listType = object : TypeToken<List<PlaybackProgress>>() {}.type

    private fun keyFor(profileId: String) = stringPreferencesKey("profile_${profileId}_playback_progress_v1")
    private fun migratedKeyFor(profileId: String) = booleanPreferencesKey("profile_${profileId}_playback_progress_migrated")

    /** Every entry for the active profile, newest first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<List<PlaybackProgress>> =
        profileManager.currentProfileId.flatMapLatest { profileId ->
            context.playbackProgressDataStore.data.map { prefs -> decode(prefs[keyFor(profileId)]) }
        }

    suspend fun all(): List<PlaybackProgress> {
        val profileId = profileManager.getProfileIdSync()
        migrateIfNeeded(profileId)
        return decode(context.playbackProgressDataStore.data.first()[keyFor(profileId)])
    }

    suspend fun get(tmdbId: Int, mediaType: MediaType, season: Int?, episode: Int?): PlaybackProgress? =
        all().firstOrNull { it.matches(tmdbId, mediaType, season, episode) }

    /** Most recently touched entry for a movie or any episode of a show. */
    suspend fun latestFor(tmdbId: Int, mediaType: MediaType): PlaybackProgress? =
        all().filter { it.tmdbId == tmdbId && it.mediaType == mediaType }.maxByOrNull { it.updatedAtMs }

    /** Episode entries for one show (watched and in-progress). */
    suspend fun episodesFor(tmdbId: Int): List<PlaybackProgress> =
        all().filter { it.tmdbId == tmdbId && it.mediaType == MediaType.TV }

    /** Continue-watching list: newest resumable entry per title, newest first. */
    suspend fun continueWatching(): List<PlaybackProgress> = continueWatchingOf(all())

    fun continueWatchingOf(entries: List<PlaybackProgress>): List<PlaybackProgress> =
        entries.sortedByDescending { it.updatedAtMs }
            .distinctBy { it.tmdbId to it.mediaType }
            .filter { it.isResumable }
            .take(Constants.MAX_CONTINUE_WATCHING)

    suspend fun save(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int?,
        episode: Int?,
        positionMs: Long,
        durationMs: Long,
        title: String
    ) {
        if (tmdbId <= 0 || durationMs <= 0L) return
        val profileId = profileManager.getProfileIdSync()
        migrateIfNeeded(profileId)
        mutex.withLock {
            context.playbackProgressDataStore.edit { prefs ->
                val key = keyFor(profileId)
                val current = decode(prefs[key])
                val previous = current.firstOrNull { it.matches(tmdbId, mediaType, season, episode) }
                val pct = ((positionMs.coerceAtLeast(0L) * 100L) / durationMs).toInt()
                val entry = PlaybackProgress(
                    tmdbId = tmdbId,
                    mediaType = mediaType,
                    season = season,
                    episode = episode,
                    positionMs = positionMs.coerceIn(0L, durationMs),
                    durationMs = durationMs,
                    updatedAtMs = System.currentTimeMillis(),
                    title = title.ifBlank { previous?.title.orEmpty() },
                    watched = pct >= Constants.WATCHED_THRESHOLD ||
                        // Re-watching a finished title: keep it "watched" until it's
                        // past the start again, then it becomes resumable.
                        (previous?.watched == true && pct < Constants.MIN_PROGRESS_THRESHOLD)
                )
                val next = (listOf(entry) + current.filterNot { it === previous })
                    .take(MAX_ENTRIES)
                prefs[key] = gson.toJson(next)
            }
        }
    }

    /**
     * Manual watched toggle (Details screen). Watched → the entry is kept with
     * `watched = true` at its full length; unwatched → the entry is dropped.
     */
    suspend fun setWatched(tmdbId: Int, mediaType: MediaType, season: Int?, episode: Int?, watched: Boolean, title: String = "") {
        if (tmdbId <= 0) return
        if (!watched) {
            if (mediaType == MediaType.TV && season != null && episode != null) {
                remove(tmdbId, mediaType, season, episode)
            } else {
                remove(tmdbId, mediaType)
            }
            return
        }
        val profileId = profileManager.getProfileIdSync()
        migrateIfNeeded(profileId)
        mutex.withLock {
            context.playbackProgressDataStore.edit { prefs ->
                val key = keyFor(profileId)
                val current = decode(prefs[key])
                val previous = current.firstOrNull { it.matches(tmdbId, mediaType, season, episode) }
                val duration = previous?.durationMs?.takeIf { it > 0L } ?: 1L
                val entry = PlaybackProgress(
                    tmdbId = tmdbId,
                    mediaType = mediaType,
                    season = season,
                    episode = episode,
                    positionMs = duration,
                    durationMs = duration,
                    updatedAtMs = System.currentTimeMillis(),
                    title = title.ifBlank { previous?.title.orEmpty() },
                    watched = true
                )
                prefs[key] = gson.toJson((listOf(entry) + current.filterNot { it === previous }).take(MAX_ENTRIES))
            }
        }
    }

    /** Drops a title (or one episode) from continue watching without marking it watched. */
    suspend fun remove(tmdbId: Int, mediaType: MediaType, season: Int? = null, episode: Int? = null) {
        val profileId = profileManager.getProfileIdSync()
        mutex.withLock {
            context.playbackProgressDataStore.edit { prefs ->
                val key = keyFor(profileId)
                val current = decode(prefs[key])
                prefs[key] = gson.toJson(current.filterNot {
                    it.tmdbId == tmdbId && it.mediaType == mediaType &&
                        (season == null || (it.season == season && it.episode == episode))
                })
            }
        }
    }

    private fun PlaybackProgress.matches(tmdbId: Int, mediaType: MediaType, season: Int?, episode: Int?): Boolean =
        this.tmdbId == tmdbId && this.mediaType == mediaType &&
            (mediaType == MediaType.MOVIE || (this.season == season && this.episode == episode))

    private fun decode(json: String?): List<PlaybackProgress> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<PlaybackProgress>>(json, listType) }.getOrNull()
            ?.filterNotNull()
            // Gson bypasses Kotlin defaults: guard against missing enum values.
            ?.filter { @Suppress("SENSELESS_COMPARISON") (it.mediaType != null) }
            ?: emptyList()
    }

    /**
     * One-time import of the inherited `local_continue_watching_v1` list (trakt_prefs).
     * Only the resume fields are read; everything else in the old schema is ignored.
     */
    private suspend fun migrateIfNeeded(profileId: String) {
        val migratedKey = migratedKeyFor(profileId)
        if (context.playbackProgressDataStore.data.first()[migratedKey] == true) return
        mutex.withLock {
            if (context.playbackProgressDataStore.data.first()[migratedKey] == true) return
            val oldJson = runCatching {
                context.traktDataStore.data.first()[stringPreferencesKey("profile_${profileId}_local_continue_watching_v1")]
            }.getOrNull()
            val imported = runCatching { parseLegacy(oldJson) }.getOrDefault(emptyList())
            context.playbackProgressDataStore.edit { prefs ->
                val key = keyFor(profileId)
                val current = decode(prefs[key])
                val merged = (current + imported.filter { old ->
                    current.none { it.matches(old.tmdbId, old.mediaType, old.season, old.episode) }
                }).sortedByDescending { it.updatedAtMs }.take(MAX_ENTRIES)
                if (merged.isNotEmpty()) prefs[key] = gson.toJson(merged)
                prefs[migratedKey] = true
            }
        }
    }

    private fun parseLegacy(json: String?): List<PlaybackProgress> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            val type = when (o.get("mediaType")?.takeIf { it.isJsonPrimitive }?.asString?.uppercase()) {
                "TV" -> MediaType.TV
                "MOVIE" -> MediaType.MOVIE
                else -> return@mapNotNull null
            }
            fun int(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asInt
            fun long(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asLong
            val posSec = long("resumePositionSeconds") ?: 0L
            val durSec = long("durationSeconds") ?: 0L
            if (durSec <= 0L) return@mapNotNull null
            PlaybackProgress(
                tmdbId = id,
                mediaType = type,
                season = if (type == MediaType.TV) int("season") else null,
                episode = if (type == MediaType.TV) int("episode") else null,
                positionMs = posSec * 1000L,
                durationMs = durSec * 1000L,
                updatedAtMs = long("updatedAtMs") ?: 0L,
                title = o.get("title")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            )
        }
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
