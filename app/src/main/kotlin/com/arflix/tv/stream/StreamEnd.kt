package com.arflix.tv.stream

/**
 * libVLC reports a cut HTTP stream as EndReached. For a stream movie, only treat that as the
 * real end when the proxy delivered everything and playback got close to the duration.
 */
object StreamEnd {
    private const val END_SLACK_MS = 30_000L

    fun isRealEnd(proxyFailed: Boolean, positionMs: Long, durationMs: Long): Boolean {
        if (proxyFailed) return false
        if (durationMs <= 0L) return true
        return positionMs >= durationMs - END_SLACK_MS
    }
}
