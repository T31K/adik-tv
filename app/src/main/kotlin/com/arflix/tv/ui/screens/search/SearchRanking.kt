package com.arflix.tv.ui.screens.search

import com.arflix.tv.data.model.MediaItem
import java.text.Normalizer
import java.util.Locale

private val accents = Regex("\\p{M}+")
private val punctuation = Regex("[^\\p{L}\\p{N}]+")
private val leadingArticle = Regex("^(the|an|a) ")

private fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(accents, "")
    .lowercase(Locale.ROOT)
    .replace("&", " and ")
    .replace("'", "")
    .replace("\u2019", "")
    .replace(punctuation, " ")
    .trim()

/** Promote title matches, keeping the provider's relevance order within each tier. */
internal fun rankSearchResults(query: String, items: List<MediaItem>): List<MediaItem> {
    val normalizedQuery = normalizeTitle(query)
    val queryWords = normalizedQuery.split(' ').filter { it.isNotEmpty() }
    return items.distinctBy { it.mediaType to it.id }.sortedBy { item ->
        val title = normalizeTitle(item.title)
        when {
            title == normalizedQuery -> 0
            title.replace(leadingArticle, "") == normalizedQuery.replace(leadingArticle, "") -> 1
            title.startsWith("$normalizedQuery ") -> 2
            " $title ".contains(" $normalizedQuery ") -> 3
            queryWords.isNotEmpty() && queryWords.all { it in title.split(' ') } -> 4
            title.startsWith(normalizedQuery) -> 5
            title.contains(normalizedQuery) -> 6
            else -> 7
        }
    }
}

/** Local fallback match: every word of the query appears in the library title (accent/case-insensitive). */
internal fun libraryTitleMatches(query: String, title: String): Boolean {
    val queryWords = normalizeTitle(query).split(' ').filter { it.isNotEmpty() }
    if (queryWords.isEmpty()) return false
    val normalizedTitle = normalizeTitle(title)
    val titleWords = normalizedTitle.split(' ')
    return queryWords.all { word -> titleWords.any { it.startsWith(word) } } ||
        normalizedTitle.contains(normalizeTitle(query))
}
