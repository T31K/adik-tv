package com.arflix.tv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide "is a movie playing right now" flag.
 *
 * Set while the VLC player screen is open. The download subsystem reads it so the
 * USB drive serves only the file being watched: drive moves, hash checks and hole
 * scans wait until playback ends.
 */
@Singleton
class PlaybackState @Inject constructor() {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    /** Suspends until no playback is active (returns immediately when idle). */
    suspend fun awaitIdle() {
        if (!_isPlaying.value) return
        _isPlaying.first { !it }
    }
}
