package com.arflix.tv.ui.screens.home

import androidx.compose.runtime.saveable.SaverScope
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class HomeFocusStateTest {
    // ADIK has no "View all" tile on Home rows (HOME_VIEW_ALL_ENABLED = false), so the
    // inherited View-all focus tests were removed.

    private fun row(vararg ids: Int) = Category("row", "Row", ids.map { MediaItem(it, "Title $it", mediaType = MediaType.MOVIE) })

    @Test fun latestInputWinsOverRefreshAndReorder() {
        val rows = listOf(row(1, 2, 3, 4))
        val state = HomeFocusState().apply { reconcile(rows) }
        state.currentItemIndex = 2
        state.recordSelection(rows)
        state.reconcile(listOf(row(9, 4, 3, 2, 1)))
        assertEquals(2, state.currentItemIndex)
        state.currentItemIndex = 3
        state.recordSelection(listOf(row(9, 4, 3, 2, 1)))
        state.reconcile(listOf(row(1, 2, 3, 4)))
        assertEquals(1, state.currentItemIndex)
    }

    @Test fun temporaryMissingItemsDoNotEraseTheAnchor() {
        val state = HomeFocusState().apply {
            currentItemIndex = 3
            recordSelection(listOf(row(1, 2, 3, 4)))
        }
        state.reconcile(listOf(row()))
        state.reconcile(listOf(row(1)))
        state.reconcile(listOf(row(4, 3, 2, 1)))
        assertEquals(0, state.currentItemIndex)
        assertEquals("MOVIE-4", state.rowItemKeysByCategoryId["row"])
    }

    @Test fun restoredStateKeepsIdentityAndNavigationIntent() {
        val state = HomeFocusState().apply {
            currentItemIndex = 1
            userHasNavigated = true
            isSidebarFocused = true
            recordSelection(listOf(row(1, 2, 3)))
        }
        val saved = with(HomeFocusState.Saver) { SaverScope { true }.save(state)!! }
        val restored = HomeFocusState.Saver.restore(saved)!!
        restored.reconcile(listOf(row(9).copy(id = "new"), row(3, 1, 2)))
        assertEquals(1, restored.currentRowIndex)
        assertEquals(2, restored.currentItemIndex)
        assertTrue(restored.userHasNavigated)
        assertTrue(restored.isSidebarFocused)
    }

    @Test fun navigationAndRenderingIgnoreInterleavedPlaceholdersEqually() {
        val real = row(1, 2).items
        val skeleton = real[0].copy(id = -1, isPlaceholder = true)
        assertEquals(real, navigableHomeItems(listOf(skeleton, real[0], skeleton, real[1])))
        assertEquals(listOf(skeleton), navigableHomeItems(listOf(skeleton)))
    }

    @Test fun returningToAnUpdatedRowUsesItsTitleInsteadOfItsOldIndex() {
        val state = HomeFocusState().apply {
            currentItemIndex = 1
            recordSelection(listOf(row(1, 2, 3)))
        }
        assertEquals(2, state.restoredItemIndex(row(3, 1, 2), 1))
    }
}
