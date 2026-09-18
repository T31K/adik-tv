package com.arflix.tv.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** Uses the existing decoder output, never a second network connection. */
class PlaybackFrameRate {
    private val mutableRate = MutableStateFlow(0f)
    val rate = mutableRate.asStateFlow()
    private var previousUs: Long? = null
    private val intervals = ArrayList<Long>(48)

    @Synchronized
    fun reset() {
        previousUs = null
        intervals.clear()
        mutableRate.value = 0f
    }

    @Synchronized
    fun onFrame(timeUs: Long, declaredRate: Float) {
        if (declaredRate.isFinite() && declaredRate in 10f..120f) {
            mutableRate.value = declaredRate
            previousUs = timeUs
            intervals.clear()
            return
        }
        val delta = previousUs?.let { timeUs - it }
        previousUs = timeUs
        if (delta == null) return
        if (delta !in 8_000L..100_000L) {
            intervals.clear()
            return
        }
        intervals.add(delta)
        if (intervals.size < 48) return
        val sorted = intervals.sorted()
        val median = sorted[sorted.size / 2]
        // Do not infer a fixed refresh rate from variable-rate or discontinuous output.
        if (intervals.count { abs(it - median) <= median * 0.02 } >= 44) {
            mutableRate.value = 1_000_000f / median
        }
        intervals.clear()
    }
}

internal fun matchingRefreshRateIndex(rates: List<Float>, fps: Float): Int? {
    if (!fps.isFinite() || fps !in 10f..120f) return null
    return rates.indices.filter { index ->
        val rate = rates[index]
        if (!rate.isFinite() || rate <= 0f) false else {
            val multiple = (rate / fps).roundToInt()
            multiple >= 1 && abs(rate / multiple - fps) <= 0.012f
        }
    }.minByOrNull { rates[it] }
}
