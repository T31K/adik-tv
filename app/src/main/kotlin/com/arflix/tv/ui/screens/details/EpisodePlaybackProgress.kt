package com.arflix.tv.ui.screens.details

import com.arflix.tv.data.model.Episode
import com.arflix.tv.data.model.EpisodeIdentity
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.PlaybackProgress

data class EpisodePlaybackProgress(
    val percent: Int,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Progress bars for the episode row: resumable [PlaybackProgress] entries (TMDB
 * season/episode) mapped onto the displayed [episodes]. Watched episodes get none.
 */
internal fun episodePlaybackProgress(
    tmdbId: Int,
    episodes: List<Episode>,
    entries: List<PlaybackProgress>,
): Map<EpisodeIdentity, EpisodePlaybackProgress> {
    if (episodes.isEmpty() || entries.isEmpty()) return emptyMap()
    val episodeByCanonicalCoordinates = episodes.associateBy {
        it.tmdbSeasonNumber to it.tmdbEpisodeNumber
    }

    return entries.asSequence()
        .filter { entry ->
            entry.mediaType == MediaType.TV &&
                entry.tmdbId == tmdbId &&
                entry.season != null &&
                entry.episode != null &&
                entry.isResumable
        }
        .sortedByDescending { it.updatedAtMs }
        .mapNotNull { entry ->
            val episode = episodeByCanonicalCoordinates[entry.season to entry.episode]
                ?: return@mapNotNull null
            if (episode.isWatched) return@mapNotNull null
            episode.identity to EpisodePlaybackProgress(
                // A resumable entry is at least 60s in: never show an empty bar for it.
                percent = entry.percent.coerceAtLeast(1),
                positionMs = entry.positionMs,
                durationMs = entry.durationMs,
            )
        }
        .distinctBy { it.first }
        .toMap()
}
