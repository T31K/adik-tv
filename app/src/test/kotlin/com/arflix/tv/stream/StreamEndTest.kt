package com.arflix.tv.stream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamEndTest {
    private val twoHours = 2 * 3600_000L

    @Test
    fun endNearTheRealEndOfAHealthyStreamIsFinished() {
        assertThat(StreamEnd.isRealEnd(proxyFailed = false, positionMs = twoHours - 5_000, durationMs = twoHours)).isTrue()
    }

    @Test
    fun endAfterTheProxyFailedIsAnError() {
        assertThat(StreamEnd.isRealEnd(proxyFailed = true, positionMs = twoHours - 5_000, durationMs = twoHours)).isFalse()
    }

    @Test
    fun endFarFromTheEndIsAnError() {
        assertThat(StreamEnd.isRealEnd(proxyFailed = false, positionMs = 40 * 60_000L, durationMs = twoHours)).isFalse()
    }

    @Test
    fun unknownDurationTrustsTheProxy() {
        assertThat(StreamEnd.isRealEnd(proxyFailed = false, positionMs = 0, durationMs = 0)).isTrue()
    }
}
