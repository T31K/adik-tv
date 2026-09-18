package com.arflix.tv.ui.screens.home

import com.arflix.tv.data.model.Category

// A slow initial refresh must not replace pages appended while enrichment was running.
internal fun preserveExtendedCatalogRows(incoming: List<Category>, current: List<Category>): List<Category> {
    val currentById = current.associateBy { it.id }
    return incoming.map { row ->
        val visible = currentById[row.id]
        if (row.id != "continue_watching" &&
            visible != null && visible.items.size > row.items.size &&
            row.items.indices.all { index ->
                val a = row.items[index]
                val b = visible.items[index]
                a.id == b.id && a.mediaType == b.mediaType
            }
        ) visible else row
    }
}
