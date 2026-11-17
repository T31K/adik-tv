package com.arflix.tv.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Player (libVLC) preferences set in Settings → Subtitles / Language. Device-wide. */
data class PlayerPrefsValues(
    /** Subtitle text size in percent of VLC's default (50..200). 115 ≈ Netflix's size on a TV. */
    val subtitleScalePercent: Int = 115,
    /** Subtitle text colour, 0xRRGGBB. */
    val subtitleColor: Int = 0xFFFFFF,
    /** Draw a dark box behind subtitle text. */
    val subtitleBackground: Boolean = false,
    /** Preferred audio language (ISO 639-2, e.g. "eng"). */
    val audioLanguage: String = "eng",
    /** Subtitle language to turn on automatically (default English); blank = subtitles off. */
    val subtitleLanguage: String = "eng"
)

object PlayerPrefs {
    private val SUB_SCALE = intPreferencesKey("vlc_sub_scale")
    private val SUB_COLOR = intPreferencesKey("vlc_sub_color")
    private val SUB_BG = booleanPreferencesKey("vlc_sub_bg")
    private val AUDIO_LANG = stringPreferencesKey("vlc_audio_lang")
    private val SUB_LANG = stringPreferencesKey("vlc_sub_lang")

    fun observe(context: Context): Flow<PlayerPrefsValues> =
        context.settingsDataStore.data.map { p ->
            PlayerPrefsValues(
                subtitleScalePercent = (p[SUB_SCALE] ?: 115).coerceIn(50, 200),
                subtitleColor = p[SUB_COLOR] ?: 0xFFFFFF,
                subtitleBackground = p[SUB_BG] ?: false,
                audioLanguage = p[AUDIO_LANG]?.takeIf { it.isNotBlank() } ?: "eng",
                // Unset → English on. Turning subtitles off in Settings stores "" explicitly.
                subtitleLanguage = p[SUB_LANG] ?: "eng"
            )
        }

    suspend fun read(context: Context): PlayerPrefsValues =
        runCatching { observe(context).first() }.getOrDefault(PlayerPrefsValues())

    suspend fun setSubtitleScale(context: Context, percent: Int) {
        context.settingsDataStore.edit { it[SUB_SCALE] = percent.coerceIn(50, 200) }
    }

    suspend fun setSubtitleColor(context: Context, rgb: Int) {
        context.settingsDataStore.edit { it[SUB_COLOR] = rgb and 0xFFFFFF }
    }

    suspend fun setSubtitleBackground(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[SUB_BG] = enabled }
    }

    suspend fun setAudioLanguage(context: Context, iso639_2: String) {
        context.settingsDataStore.edit { it[AUDIO_LANG] = iso639_2 }
    }

    /** Blank turns automatic subtitles off. */
    suspend fun setSubtitleLanguage(context: Context, iso639_2: String) {
        context.settingsDataStore.edit { it[SUB_LANG] = iso639_2 }
    }
}
