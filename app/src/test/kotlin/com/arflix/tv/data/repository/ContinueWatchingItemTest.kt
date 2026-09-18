package com.arflix.tv.data.repository

import com.arflix.tv.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingItemTest {

    @Test
    fun toMediaItem_upNextDoesNotDeriveResumeTimeFromShowProgress() {
        val item = ContinueWatchingItem(
            id = 123,
            title = "Example Show",
            mediaType = MediaType.TV,
            progress = 55,
            resumePositionSeconds = 0L,
            durationSeconds = 2700L,
            season = 4,
            episode = 29,
            isUpNext = true
        )

        val mediaItem = item.toMediaItem()

        assertEquals("Continue S4E29", mediaItem.subtitle)
        assertFalse(mediaItem.showPlaybackProgress)
        assertNull(mediaItem.timeRemainingLabel)
    }

    @Test
    fun toMediaItem_inProgressEpisodeCanStillUsePlaybackProgress() {
        val item = ContinueWatchingItem(
            id = 123,
            title = "Example Show",
            mediaType = MediaType.TV,
            progress = 50,
            resumePositionSeconds = 0L,
            durationSeconds = 2700L,
            season = 1,
            episode = 2
        )

        val mediaItem = item.toMediaItem()

        assertEquals("Continue S1E2 from 22:30", mediaItem.subtitle)
        assertEquals("22min left", mediaItem.timeRemainingLabel)
    }

    @Test
    fun toMediaItem_keepsEpisodeStillSeparateFromSeriesArtwork() {
        val item = ContinueWatchingItem(
            id = 123,
            title = "Example Show",
            mediaType = MediaType.TV,
            progress = 25,
            season = 2,
            episode = 3,
            backdropPath = "https://images.example/show.jpg",
            episodeStillPath = "https://images.example/s02e03.jpg"
        )

        val mediaItem = item.toMediaItem()

        assertEquals("https://images.example/show.jpg", mediaItem.backdrop)
        assertEquals("https://images.example/s02e03.jpg", mediaItem.episodeStill)
    }

    @Test
    fun toMediaItem_withoutEpisodeStillRetainsSeriesFallback() {
        val item = ContinueWatchingItem(
            id = 123,
            title = "Example Show",
            mediaType = MediaType.TV,
            progress = 25,
            season = 2,
            episode = 3,
            backdropPath = "https://images.example/show.jpg"
        )

        val mediaItem = item.toMediaItem()

        assertEquals("https://images.example/show.jpg", mediaItem.backdrop)
        assertNull(mediaItem.episodeStill)
    }
}
