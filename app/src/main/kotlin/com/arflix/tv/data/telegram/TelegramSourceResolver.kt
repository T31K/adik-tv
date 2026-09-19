package com.arflix.tv.data.telegram

import com.arflix.tv.data.model.StreamSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADIK: Telegram sourcing removed. The fork never shipped TDLib credentials
 * (placeholders in secrets.properties), so this path could never activate.
 * Kept as a no-op stub so StreamRepository needs no changes; the TDLib
 * native libs (~37 MB in-APK) and generated Java bindings are deleted.
 */
@Singleton
class TelegramSourceResolver @Inject constructor() {

    fun isEnabled(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun resolve(
        title: String,
        year: Int?,
        season: Int? = null,
        episode: Int? = null,
        imdbId: String = "",
        isMovie: Boolean = true
    ): List<StreamSource> = emptyList()
}
