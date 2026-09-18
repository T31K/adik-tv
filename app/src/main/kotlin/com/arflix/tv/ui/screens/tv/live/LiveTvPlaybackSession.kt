package com.arflix.tv.ui.screens.tv.live

import androidx.media3.common.Player

/** Pausing alone leaves the stream loader alive when Android keeps the screen in memory. */
internal class LiveTvPlaybackSession(
    private val player: Player,
    private val cancelRequests: () -> Unit,
) {
    private var suspended = false
    private var resumePlayback = false

    fun suspend() {
        if (suspended) return
        suspended = true
        resumePlayback = player.playWhenReady
        player.pause()
        player.stop()
        cancelRequests()
    }

    fun resume(isLive: Boolean, allowed: Boolean) {
        if (!suspended || !allowed) return
        suspended = false
        if (player.mediaItemCount == 0) return
        if (isLive) player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = resumePlayback
    }
}
