package com.arflix.tv.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.arflix.tv.R

/**
 * ADIK: subtle UI hover/navigation sound, played when a focusable gains focus.
 * SoundPool keeps the tiny clip in memory for zero-latency, overlap-safe playback.
 */
object NavSound {
    @Volatile private var soundPool: SoundPool? = null
    @Volatile private var soundId: Int = 0
    @Volatile private var loaded: Boolean = false
    @Volatile var enabled: Boolean = true

    fun init(context: Context) {
        if (soundPool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        sp.setOnLoadCompleteListener { _, _, status -> if (status == 0) loaded = true }
        runCatching { soundId = sp.load(context.applicationContext, R.raw.nav_hover, 1) }
        soundPool = sp
    }

    fun play() {
        if (!enabled || !loaded) return
        runCatching { soundPool?.play(soundId, 0.3f, 0.3f, 1, 0, 1f) }
    }
}
