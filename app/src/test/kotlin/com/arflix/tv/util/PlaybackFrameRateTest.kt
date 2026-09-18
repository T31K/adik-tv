package com.arflix.tv.util

import org.junit.Assert.*
import org.junit.Test

class PlaybackFrameRateTest {
    @Test fun firstPlaybackUsesDeclaredRateImmediately() {
        val detector = PlaybackFrameRate()
        detector.onFrame(0, 24000f / 1001f)
        assertEquals(24000f / 1001f, detector.rate.value, 0.001f)
    }

    @Test fun missingContainerRateUsesDecodedTimestamps() {
        val detector = PlaybackFrameRate()
        repeat(49) { detector.onFrame(it * 40_000L, -1f) }
        assertEquals(25f, detector.rate.value, 0.001f)
    }

    @Test fun detectsFractionalFilmRate() {
        val detector = PlaybackFrameRate()
        repeat(49) { detector.onFrame(it * 41_708L, Float.NaN) }
        assertEquals(24000f / 1001f, detector.rate.value, 0.001f)
    }

    @Test fun resetDoesNotReusePreviousSourceRate() {
        val detector = PlaybackFrameRate()
        detector.onFrame(0, 25f)
        detector.reset()
        assertEquals(0f, detector.rate.value, 0f)
        detector.onFrame(0, 50f)
        assertEquals(50f, detector.rate.value, 0f)
    }

    @Test fun seekDiscontinuityDoesNotInventFrameRate() {
        val detector = PlaybackFrameRate()
        repeat(30) { detector.onFrame(it * 40_000L, -1f) }
        repeat(30) { detector.onFrame(300_000_000L + it * 40_000L, -1f) }
        assertEquals(0f, detector.rate.value, 0f)
    }

    @Test fun variableFrameTimingIsNotTreatedAsFixedRate() {
        val detector = PlaybackFrameRate()
        var time = 0L
        repeat(80) {
            time += if (it % 2 == 0) 40_000L else 60_000L
            detector.onFrame(time, -1f)
        }
        assertEquals(0f, detector.rate.value, 0f)
    }

    @Test fun choosesFilmAndPalModesInsteadOfSixty() {
        val rates = listOf(60f, 50f, 24f, 23.976f)
        assertEquals(2, matchingRefreshRateIndex(rates, 24f))
        assertEquals(3, matchingRefreshRateIndex(rates, 24000f / 1001f))
        assertEquals(1, matchingRefreshRateIndex(rates, 25f))
    }

    @Test fun supportsHigherIntegerMultiplesWithoutPulldown() {
        assertEquals(1, matchingRefreshRateIndex(listOf(60f, 120f), 24f))
        assertEquals(1, matchingRefreshRateIndex(listOf(60f, 100f), 25f))
        assertNull(matchingRefreshRateIndex(listOf(60f), 24f))
    }

    @Test fun rejectsInvalidRates() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertNull(matchingRefreshRateIndex(listOf(60f), it))
        }
    }
}
