package com.arflix.tv.ui.screens.player

/**
 * Turns libVLC track names ("Track 1 - [Japanese]", "Track 2 - SDH - [English]") into what the
 * Audio & Subtitles menu shows: just the language, plus any real description, numbered when a
 * language appears twice.
 */
object TrackNames {
    private val TRACK_PREFIX = Regex("""^Track \d+\s*(-\s*)?""", RegexOption.IGNORE_CASE)
    private val LANGUAGE = Regex("""\s*-?\s*\[([^\]]+)]\s*$""")

    fun label(raw: String): String {
        val lang = LANGUAGE.find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val desc = raw.replace(LANGUAGE, "").replace(TRACK_PREFIX, "").trim().trim('-').trim()
        return when {
            lang.isEmpty() -> desc.ifEmpty { raw.trim() }
            desc.isEmpty() || desc.equals(lang, ignoreCase = true) -> lang
            desc.contains(lang, ignoreCase = true) -> desc
            else -> "$lang ($desc)"
        }
    }

    fun labels(raw: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return raw.map { name ->
            val base = label(name)
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            if (n == 1) base else "$base $n"
        }
    }
}
