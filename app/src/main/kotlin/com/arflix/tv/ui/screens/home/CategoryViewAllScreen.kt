package com.arflix.tv.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvGridItemSpan
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.itemsIndexed
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.material3.Text
import com.arflix.tv.R
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.ui.components.CardLayoutMode
import com.arflix.tv.ui.components.LocalBottomBarInset
import com.arflix.tv.ui.components.MediaCard
import com.arflix.tv.ui.components.rememberCatalogueRowLayoutMode
import com.arflix.tv.ui.focus.arvioDpadFocusGroup
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.util.LocalDeviceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Full paginated grid for one home row, opened from the row's "View all" card.
 *
 * Shares the Home screen's [HomeViewModel] (scoped to the Home back-stack entry), the same
 * way Nuvio's CatalogSeeAllScreen shares its home ViewModel: pages loaded here also extend
 * the home row, and the row's pagination state stays in one place.
 */
@Composable
fun CategoryViewAllScreen(
    categoryId: String,
    viewModel: HomeViewModel,
    onNavigateToDetails: (MediaType, Int) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cardLogoUrls = viewModel.cardLogoUrls
    val category = uiState.categories.firstOrNull { it.id == categoryId }
    val items = remember(category?.items) {
        category?.items?.filterNot { it.isPlaceholder }.orEmpty()
    }
    val itemKeys = remember(categoryId, items) { stableHomeRowItemKeys(categoryId, items) }
    // No entry means the row has never paged yet, which loadNextPageForCategory treats as "may have more".
    val hasMore = uiState.categoryHasMoreMap[categoryId] != false
    val isMobile = LocalDeviceType.current.isTouchDevice()
    val usePosterCards = rememberCatalogueRowLayoutMode("home:$categoryId") == CardLayoutMode.POSTER

    BackHandler(onBack = onBack)

    val gridState = rememberTvLazyGridState()
    var lastFocusedIndex by rememberSaveable(categoryId) { mutableIntStateOf(0) }
    var pendingFocusIndex by remember { mutableIntStateOf(-1) }
    val latestItemCount by rememberUpdatedState(items.size)

    // TV: focus the first card on entry, and the last focused card when returning from Details.
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner, isMobile) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !isMobile) {
                coroutineScope.launch {
                    // Clear the 280ms enter / pop-enter fade before touching the focus tree.
                    delay(300)
                    val target = lastFocusedIndex.coerceIn(0, (latestItemCount - 1).coerceAtLeast(0))
                    runCatching { gridState.scrollToItem(target) }
                    pendingFocusIndex = target
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openItem: (MediaItem) -> Unit = { item ->
        // Same warm-cache handoff as Home, so Details opens without a blank frame.
        viewModel.cacheItem(item)
        cardLogoUrls["${item.mediaType}_${item.id}"]
            ?.takeIf { it.isNotBlank() }
            ?.let { viewModel.cacheLogoUrl(item.mediaType, item.id, it) }
        onNavigateToDetails(item.mediaType, item.id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundDark())
    ) {
        Text(
            text = category?.let { localizedCategoryTitle(it) }.orEmpty(),
            style = ArflixTypography.sectionTitle.copy(
                fontSize = if (isMobile) 20.sp else 26.sp,
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .then(if (isMobile) Modifier.statusBarsPadding() else Modifier)
                .padding(
                    start = if (isMobile) 16.dp else 42.dp,
                    end = if (isMobile) 16.dp else 42.dp,
                    top = if (isMobile) 12.dp else 28.dp,
                    bottom = 12.dp
                )
        )

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (category != null && hasMore) {
                    CircularProgressIndicator(
                        color = Color(0xFF4F7FB0),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.collection_empty),
                        color = TextSecondary,
                        style = ArflixTypography.body
                    )
                }
            }
            return@Column
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val horizontalPadding = if (isMobile) 16.dp else 42.dp
            val spacing = if (usePosterCards) 18.dp else 14.dp
            // Fit as many columns as the home card size allows, then stretch cards to fill the width.
            val minCardWidth = when {
                usePosterCards -> if (isMobile) 105.dp else 120.dp
                else -> if (isMobile) 150.dp else 180.dp
            }
            val availableWidth = maxWidth - horizontalPadding * 2
            val gridColumns = ((availableWidth + spacing) / (minCardWidth + spacing)).toInt().coerceAtLeast(2)
            val cardWidth = (availableWidth - spacing * (gridColumns - 1)) / gridColumns
            val lookAheadItems = gridColumns * 3

            // Page ahead of the end of the grid and prefetch logos around what's visible.
            LaunchedEffect(gridState, categoryId, lookAheadItems) {
                snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                    .distinctUntilChanged()
                    .collect { lastVisible ->
                        viewModel.onViewAllVisiblePosition(categoryId, lastVisible, lookAheadItems)
                    }
            }

            TvLazyVerticalGrid(
                columns = TvGridCells.Fixed(gridColumns),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .arvioDpadFocusGroup()
                    .clipToBounds(),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    top = 8.dp,
                    end = horizontalPadding,
                    bottom = 48.dp + LocalBottomBarInset.current
                ),
                verticalArrangement = Arrangement.spacedBy(spacing),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                itemsIndexed(
                    items,
                    key = { index, _ -> itemKeys[index] },
                    contentType = { _, _ -> if (usePosterCards) "poster_card" else "landscape_card" }
                ) { index, item ->
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(pendingFocusIndex) {
                        if (pendingFocusIndex == index) {
                            delay(50)
                            runCatching { focusRequester.requestFocus() }
                            pendingFocusIndex = -1
                        }
                    }
                    MediaCard(
                        item = item,
                        width = cardWidth,
                        isLandscape = !usePosterCards,
                        logoImageUrl = cardLogoUrls["${item.mediaType}_${item.id}"],
                        showTitle = true,
                        titleMaxLines = if (usePosterCards) 2 else 1,
                        onFocused = { lastFocusedIndex = index },
                        onClick = { openItem(item) },
                        modifier = Modifier.focusRequester(focusRequester)
                    )
                }

                if (hasMore) {
                    item(
                        span = { TvGridItemSpan(maxLineSpan) },
                        contentType = "loading_more"
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF4F7FB0),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
