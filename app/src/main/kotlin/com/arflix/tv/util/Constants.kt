package com.arflix.tv.util

import com.arflix.tv.BuildConfig

/**
 * Application constants.
 *
 * API keys come from ignored local secrets or CI environment values, not from
 * committed source.
 */
object Constants {
    // API base URLs.
    const val TMDB_BASE_URL = "https://api.themoviedb.org/3/"
    // ADIK content feed (Postgres-backed, served by main-server under /adik; the
    // legacy /megaflix mount serves the same data). Paths live in MegaflixFeedApi.
    const val MEGAFLIX_FEED_BASE_URL = "https://api.kaleidoscopical.com/"
    const val MEGAFLIX_FEED_TOKEN = "mgfx_7b2cedc4b5f053072628a02e152d8a87"
    // This TV's fleet id is TYPED into Settings by the owner (never hardcoded
    // here — the valid list lives only in main-server routes/megaflix.js).

    private fun usableSecret(value: String): String =
        value.takeUnless { candidate ->
            candidate.isBlank() || candidate.startsWith("your-", ignoreCase = true)
        } ?: ""

    // TMDB key from secrets.properties (BuildConfig via the secrets plugin).
    val TMDB_API_KEY: String get() = usableSecret(BuildConfig.TMDB_API_KEY)

    // Image URLs - tuned for TV quality with smooth scrolling/perf.
    const val IMAGE_BASE = "https://image.tmdb.org/t/p/w780"
    const val IMAGE_BASE_LARGE = "https://image.tmdb.org/t/p/w1280"
    const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
    // Full quality for hero and detail backdrops; loading speed is handled by
    // preloading and disk caching instead of resolution downgrade.
    const val BACKDROP_BASE_LARGE = "https://image.tmdb.org/t/p/original"
    const val LOGO_BASE = "https://image.tmdb.org/t/p/w500"
    const val LOGO_BASE_LARGE = "https://image.tmdb.org/t/p/original"

    // Progress thresholds.
    const val WATCHED_THRESHOLD = 90
    const val MIN_PROGRESS_THRESHOLD = 3
    const val MAX_PROGRESS_ENTRIES = 50
    const val MAX_CONTINUE_WATCHING = 50

    // Preferences keys.
    const val PREFS_NAME = "arflix_prefs"
    const val PREF_DEFAULT_SUBTITLE = "default_subtitle"
    const val PREF_AUTO_PLAY_NEXT = "auto_play_next"
}

/**
 * Language code mappings.
 */
object LanguageMap {
    private val ISO_LANG_MAP = mapOf(
        "ar" to "Arabic", "ara" to "Arabic",
        "bn" to "Bengali", "ben" to "Bengali",
        "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese",
        "nl" to "Dutch", "nld" to "Dutch", "dut" to "Dutch",
        "en" to "English", "eng" to "English",
        "fr" to "French", "fre" to "French", "fra" to "French",
        "de" to "German", "ger" to "German", "deu" to "German",
        "gu" to "Gujarati", "guj" to "Gujarati",
        "hi" to "Hindi", "hin" to "Hindi",
        "it" to "Italian", "ita" to "Italian",
        "ja" to "Japanese", "jpn" to "Japanese",
        "kn" to "Kannada", "kan" to "Kannada",
        "ko" to "Korean", "kor" to "Korean",
        "ml" to "Malayalam", "mal" to "Malayalam",
        "mr" to "Marathi", "mar" to "Marathi",
        "pl" to "Polish", "pol" to "Polish",
        "pt" to "Portuguese", "por" to "Portuguese",
        "pa" to "Punjabi", "pan" to "Punjabi",
        "ru" to "Russian", "rus" to "Russian",
        "es" to "Spanish", "spa" to "Spanish",
        "sv" to "Swedish", "swe" to "Swedish",
        "ta" to "Tamil", "tam" to "Tamil",
        "te" to "Telugu", "tel" to "Telugu",
        "th" to "Thai", "tha" to "Thai",
        "tr" to "Turkish", "tur" to "Turkish"
    )

    fun getLanguageName(code: String): String {
        return ISO_LANG_MAP[code.lowercase()] ?: code.uppercase()
    }
}
