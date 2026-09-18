package com.arflix.tv.ui.screens.home

import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import org.junit.Assert.*
import org.junit.Test

class HomeCatalogRowsTest {
    private fun row(ids: IntRange, id: String = "custom") = Category(id, id, ids.map { MediaItem(it, "$it") })

    @Test fun `late initial result preserves appended page`() {
        assertEquals(28, preserveExtendedCatalogRows(listOf(row(1..8)), listOf(row(1..28))).single().items.size)
    }

    @Test fun `changed provider order and continue watching are refreshed`() {
        assertEquals(8, preserveExtendedCatalogRows(listOf(row(2..9)), listOf(row(1..28))).single().items.size)
        assertEquals(8, preserveExtendedCatalogRows(listOf(row(1..8, "continue_watching")), listOf(row(1..28, "continue_watching"))).single().items.size)
    }

    @Test fun `late placeholder does not erase a loaded deferred catalogue`() {
        assertEquals(20, preserveExtendedCatalogRows(listOf(row(1..0)), listOf(row(1..20))).single().items.size)
    }
}
