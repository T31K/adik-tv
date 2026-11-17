package com.arflix.tv.data.model

import androidx.compose.runtime.Immutable
import java.io.Serializable

/**
 * Media item - represents a movie or TV show
 * Matches webapp's MediaItem type
 */
@Immutable
data class MediaItem(
    val id: Int,
    val title: String,
    val subtitle: String = "",
    val overview: String = "",
    val year: String = "",
    val releaseDate: String? = null,
    val rating: String = "",
    // Age certification, already labelled for display ("FSK 16", "R", "US · R").
    // Null when TMDB has none for the content country or the US fallback.
    val contentRating: String? = null,
    val duration: String = "",
    val imdbRating: String = "",
    val tmdbRating: String = "",
    val mediaType: MediaType = MediaType.MOVIE,
    val image: String = "",
    val backdrop: String? = null,
    // Episode-specific landscape artwork for Continue Watching cards. Keeping
    // it separate prevents episode stills from replacing series hero artwork.
    val episodeStill: String? = null,
    val progress: Int = 0,
    val isWatched: Boolean = false,
    val badge: String? = null,
    val genreIds: List<Int> = emptyList(),
    val originalLanguage: String? = null,
    // The native TMDB name, kept next to the localized [title] because some
    // providers list a title only under its original name. Null when TMDB has
    // none, and null on items restored from an older JSON cache - every reader
    // must treat it as "unknown", never as "same as the title".
    val originalTitle: String? = null,
    val primaryNetworkLogo: String? = null,
    val isOngoing: Boolean = false,
    val totalEpisodes: Int? = null,
    val watchedEpisodes: Int? = null,
    val nextEpisode: NextEpisode? = null,
    // Additional movie-specific fields
    val budget: Long? = null,
    val revenue: Long? = null,
    // TV show status
    val status: String? = null, // "Returning Series", "Ended", "Canceled"
    // Character name (for person filmography / known for)
    val character: String = "",
    // Popularity score from TMDB (higher = more mainstream content)
    val popularity: Float = 0f,
    // Source-specific added timestamp, used for exact newest-first watchlist ordering.
    val addedAt: Long = 0L,
    // Explicit source order when a remote list already gives the correct order.
    val sourceOrder: Int = Int.MAX_VALUE,
    // Placeholder card - shows skeleton loading animation
    val isPlaceholder: Boolean = false,
    // Continue Watching: formatted time remaining (e.g., "23min left", "1hr 15min left")
    val timeRemainingLabel: String? = null,
    // Continue Watching: true only when progress represents current movie/episode playback.
    val showPlaybackProgress: Boolean = true,
    // Megaflix: when non-null, this item is a local file — play this URI directly.
    // Metadata still comes from TMDB via `id`.
    val localUri: String? = null,
    // Megaflix: download state for the feed-driven library (default READY so all
    // non-library items — search results, TMDB rows — render as normal playable cards).
    val downloadStatus: DownloadStatus = DownloadStatus.READY,
    val downloadProgress: Float = 0f,
    // Megaflix: TMDB production-company ids, for studio rows (Netflix/Disney/…).
    val companyIds: List<Int> = emptyList(),
) : Serializable

enum class MediaType {
    @com.google.gson.annotations.SerializedName(value = "MOVIE", alternate = ["movie"])
    MOVIE,
    @com.google.gson.annotations.SerializedName(value = "TV", alternate = ["tv"])
    TV
}

/**
 * Next episode to watch
 */
@Immutable
data class NextEpisode(
    val id: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val overview: String = ""
) : Serializable

/**
 * Episode details
 */
@Immutable
data class EpisodeIdentity(
    val displaySeason: Int,
    val displayEpisode: Int,
    val tmdbSeason: Int,
    val tmdbEpisode: Int,
    val kitsuId: Int? = null,
    val kitsuEpisode: Int? = null,
    val armEpisodeId: Int? = null
) : Serializable {
    val kitsuQuery: String?
        get() = kitsuId?.let { id -> kitsuEpisode?.let { episode -> "kitsu:$id:$episode" } }

    companion object {
        fun canonical(season: Int, episode: Int) = EpisodeIdentity(
            displaySeason = season,
            displayEpisode = episode,
            tmdbSeason = season,
            tmdbEpisode = episode
        )
    }
}

@Immutable
data class Episode(
    val id: Int,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val name: String,
    val overview: String = "",
    val stillPath: String? = null,
    val voteAverage: Float = 0f,
    val imdbRating: String = "",
    val runtime: Int = 0,
    val airDate: String = "",
    val isWatched: Boolean = false,
    /** Single source of truth for display, TMDB and anime-provider coordinates. */
    val identity: EpisodeIdentity = EpisodeIdentity.canonical(seasonNumber, episodeNumber)
) : Serializable {
    val tmdbSeasonNumber: Int get() = identity.tmdbSeason
    val tmdbEpisodeNumber: Int get() = identity.tmdbEpisode
    val kitsuId: Int? get() = identity.kitsuId
    val kitsuEpisodeNumber: Int? get() = identity.kitsuEpisode
}

/**
 * Cast member
 */
@Immutable
data class CastMember(
    val id: Int,
    val name: String,
    val character: String = "",
    val profilePath: String? = null
) : Serializable

/**
 * Community review shown on details pages.
 */
@Immutable
data class Review(
    val id: String,
    val author: String,
    val authorUsername: String = "",
    val authorAvatar: String? = null,
    val content: String,
    val rating: Float? = null,
    val createdAt: String = ""
) : Serializable

/**
 * Person details (for cast modal)
 */
@Immutable
data class PersonDetails(
    val id: Int,
    val name: String,
    val biography: String = "",
    val placeOfBirth: String? = null,
    val birthday: String? = null,
    val profilePath: String? = null,
    val knownFor: List<MediaItem> = emptyList()
) : Serializable

/**
 * Category/Row of media items
 */
@Immutable
data class Category(
    val id: String,
    val title: String,
    val items: List<MediaItem>
) : Serializable
