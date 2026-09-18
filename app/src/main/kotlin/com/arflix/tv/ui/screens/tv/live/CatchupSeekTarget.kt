package com.arflix.tv.ui.screens.tv.live

internal fun catchupSeekTarget(
    currentMs: Long,
    deltaMs: Long,
    durationMs: Long,
    seekable: Boolean,
    urlGranularityMs: Long,
): Long {
    val last = (durationMs - 1_000L).coerceAtLeast(0L)
    val target = (currentMs + deltaMs).coerceIn(0L, last)
    if (seekable || deltaMs == 0L) return target
    // Non-seekable transport streams must reopen at a provider-supported timestamp.
    val step = urlGranularityMs.coerceAtLeast(1_000L)
    val aligned = if (deltaMs > 0L) ((target + step - 1L) / step) * step else (target / step) * step
    val bounded = aligned.coerceAtMost((last / step) * step)
    return if ((deltaMs > 0L && bounded <= currentMs) || (deltaMs < 0L && bounded >= currentMs)) {
        currentMs
    } else bounded
}
