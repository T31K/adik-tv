package com.arflix.tv.ui.screens.details

import com.arflix.tv.data.model.Episode
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.PlaybackProgress
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpisodePlaybackProgressTest {
    private val episode = Episode(
        id = 7,
        episodeNumber = 7,
        seasonNumber = 2,
        name = "Episode 7",
    )

    @Test
    fun usesExactEpisodePositionForCardProgressAndResume() {
        val result = episodePlaybackProgress(
            tmdbId = 123,
            episodes = listOf(episode),
            entries = listOf(entry(positionSec = 1_200, durationSec = 2_400)),
        )[episode.identity]

        assertThat(result?.percent).isEqualTo(50)
        assertThat(result?.positionMs).isEqualTo(1_200_000L)
    }

    @Test
    fun watchedEpisodeDoesNotExposePartialProgress() {
        val watched = episode.copy(isWatched = true)

        val result = episodePlaybackProgress(
            tmdbId = 123,
            episodes = listOf(watched),
            entries = listOf(entry(positionSec = 1_200, durationSec = 2_400)),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun accidentalShortPlayIsIgnored() {
        val result = episodePlaybackProgress(
            tmdbId = 123,
            episodes = listOf(episode),
            entries = listOf(entry(positionSec = 20, durationSec = 2_400)),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun watchedThresholdIsNotRenderedAsPartialProgress() {
        val result = episodePlaybackProgress(
            tmdbId = 123,
            episodes = listOf(episode),
            entries = listOf(entry(positionSec = 2_280, durationSec = 2_400)),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun otherShowsAreIgnored() {
        val result = episodePlaybackProgress(
            tmdbId = 999,
            episodes = listOf(episode),
            entries = listOf(entry(positionSec = 1_200, durationSec = 2_400)),
        )

        assertThat(result).isEmpty()
    }

    private fun entry(positionSec: Long, durationSec: Long) = PlaybackProgress(
        tmdbId = 123,
        mediaType = MediaType.TV,
        season = 2,
        episode = 7,
        positionMs = positionSec * 1000L,
        durationMs = durationSec * 1000L,
        updatedAtMs = 1L,
    )
}
