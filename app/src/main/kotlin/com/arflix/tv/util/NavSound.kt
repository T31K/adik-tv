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
    @Volatile private var hoverId: Int = 0
    @Volatile private var selectId: Int = 0
    private val ready = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    @Volatile var enabled: Boolean = true

    fun init(context: Context) {
        if (soundPool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        sp.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) ready.add(sampleId) }
        runCatching { hoverId = sp.load(context.applicationContext, R.raw.nav_hover, 1) }
        runCatching { selectId = sp.load(context.applicationContext, R.raw.nav_select, 1) }
        soundPool = sp
    }

    /** Focus/hover move. */
    fun play() {
        if (!enabled || hoverId !in ready) return
        runCatching { soundPool?.play(hoverId, 0.3f, 0.3f, 1, 0, 1f) }
    }

    /** Item selected / clicked. */
    fun playSelect() {
        if (!enabled || selectId !in ready) return
        runCatching { soundPool?.play(selectId, 0.45f, 0.45f, 1, 0, 1f) }
    }
}
