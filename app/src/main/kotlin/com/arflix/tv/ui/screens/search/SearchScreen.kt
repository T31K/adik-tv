package com.arflix.tv.ui.screens.search

import com.arflix.tv.ui.components.LocalBottomBarInset
import androidx.activity.compose.BackHandler
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.arflix.tv.R

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Category
import com.arflix.tv.ui.components.LoadingIndicator
import com.arflix.tv.ui.components.CardLayoutMode
import com.arflix.tv.ui.components.AppTopBar
import com.arflix.tv.ui.components.AppTopBarContentTopInset
import com.arflix.tv.ui.components.MediaCard
import com.arflix.tv.ui.components.SidebarItem
import com.arflix.tv.ui.components.topBarFocusedItem
import com.arflix.tv.ui.components.topBarMaxIndex
import com.arflix.tv.ui.components.rememberCatalogueRowLayoutMode
import com.arflix.tv.ui.focus.arvioDpadFocusGroup
import com.arflix.tv.ui.skin.ArvioFocusableSurface
import com.arflix.tv.ui.skin.ArvioSkin
import com.arflix.tv.ui.skin.rememberArvioCardShape
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.BackgroundCard
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.util.LocalDeviceType

/**
 * Display-only localization of the library browse rows built in `SearchViewModel.loadLibrary`.
 * The English title stays in [Category.title]; the row id picks the rendered label.
 */
@Composable
private fun localizedRowTitle(category: Category): String = when (category.id) {
    LIBRARY_MOVIES_ROW_ID -> stringResource(R.string.movies)
    LIBRARY_TV_ROW_ID -> stringResource(R.string.tv_shows)
    else -> category.title
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    currentProfile: com.arflix.tv.data.model.Profile? = null,
    onNavigateToDetails: (MediaType, Int) -> Unit = { _, _ -> },
    onNavigateToHome: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val isCompactHeight = configuration.screenHeightDp <= 780
    val isTouchDevice = LocalDeviceType.current.isTouchDevice()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // ADIK: bigger search bar now that the filter row is gone.
    val searchBarWidth = if (isTouchDevice) configuration.screenWidthDp.dp - 32.dp
        else (configuration.screenWidthDp.dp * 0.65f).coerceIn(560.dp, 900.dp)

    val hasSearchResults = uiState.movieResults.isNotEmpty() || uiState.tvResults.isNotEmpty() || uiState.personResults.isNotEmpty()
    val searchTopResults = uiState.results

    // Determine which categories to show in rows (filter out empty ones)
    val activeCategories: List<Category> = when {
        hasSearchResults -> {
            val list = mutableListOf<Category>()
            if (searchTopResults.isNotEmpty()) list.add(Category("s_all", "${stringResource(R.string.search)} (${searchTopResults.size})", searchTopResults))
            if (uiState.movieResults.isNotEmpty()) list.add(Category("s_m", "${stringResource(R.string.movies)} (${uiState.movieResults.size})", uiState.movieResults))
            if (uiState.tvResults.isNotEmpty()) list.add(Category("s_t", "${stringResource(R.string.tv_shows)} (${uiState.tvResults.size})", uiState.tvResults))
            list.addAll(uiState.personResults)
            list
        }
        uiState.query.isEmpty() -> uiState.discoverCategories.filter { it.items.isNotEmpty() }
        else -> emptyList()
    }
    val activeLogoUrls: Map<String, String> = when {
        hasSearchResults -> uiState.cardLogoUrls
        else -> uiState.discoverLogoUrls
    }

    var focusZone by rememberSaveable { mutableStateOf(FocusZone.SEARCH_INPUT) }
    val hasProfile = currentProfile != null
    val maxSidebarIndex = topBarMaxIndex(hasProfile)
    var sidebarFocusIndex by remember { mutableIntStateOf(if (hasProfile) 1 else 0) }
    var isSearchInputFocused by remember { mutableStateOf(false) }
    var suppressSelectUntilMs by remember { mutableLongStateOf(0L) }
    val fastScrollThresholdMs = 220L

    // Manual row/item focus tracking (like HomeScreen)
    var currentRowIndex by rememberSaveable(uiState.query) { mutableIntStateOf(0) }
    var currentItemIndex by rememberSaveable(uiState.query) { mutableIntStateOf(0) }
    val rowPositions = rememberSaveable(uiState.query) { mutableMapOf<String, Int>() }
    var enterResultsOnLoad by remember { mutableStateOf(false) }
    var consumedDpadKey by remember { mutableStateOf<Key?>(null) }
    var resultsLastNavEventTime by remember { mutableLongStateOf(0L) }
    var isSearchEditing by remember { mutableStateOf(false) }
    var searchEditRequestNonce by remember { mutableIntStateOf(0) }

    val searchFocusRequester = remember { FocusRequester() }
    val resultsFocusRequester = remember { FocusRequester() }
    val textInputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(uiState.query, activeCategories.size) {
        currentRowIndex = currentRowIndex.coerceIn(0, (activeCategories.size - 1).coerceAtLeast(0))
        val maxItem = (activeCategories.getOrNull(currentRowIndex)?.items?.size ?: 1) - 1
        currentItemIndex = currentItemIndex.coerceIn(0, maxItem.coerceAtLeast(0))
    }

    LaunchedEffect(uiState.isLoading, enterResultsOnLoad) {
        if (enterResultsOnLoad && !uiState.isLoading) {
            enterResultsOnLoad = false
            if (activeCategories.isNotEmpty()) {
                focusZone = FocusZone.RESULTS
                resultsLastNavEventTime = SystemClock.elapsedRealtime()
            }
        }
    }
    LaunchedEffect(focusZone, activeCategories.isNotEmpty(), isSearchEditing) {
        if (!isTouchDevice && focusZone == FocusZone.RESULTS && activeCategories.isNotEmpty() && !isSearchEditing) {
            resultsFocusRequester.requestFocus()
        }
    }

    fun moveResultRow(offset: Int) {
        activeCategories.getOrNull(currentRowIndex)?.let { rowPositions[it.id] = currentItemIndex }
        currentRowIndex = (currentRowIndex + offset).coerceIn(0, (activeCategories.size - 1).coerceAtLeast(0))
        val row = activeCategories.getOrNull(currentRowIndex)
        currentItemIndex = (rowPositions[row?.id] ?: currentItemIndex)
            .coerceIn(0, (row?.items?.lastIndex ?: 0).coerceAtLeast(0))
        resultsLastNavEventTime = SystemClock.elapsedRealtime()
    }

    LaunchedEffect(isTouchDevice) {
        // FocusRequester can throw IllegalStateException if the target composable
        // hasn't been placed yet (e.g. zero-sized keyboard on cold start, or when
        // the screen is composed then immediately navigated away). Swallow that
        // specific case so it doesn't surface to the user as a crash — TalkBack
        // focus will re-claim on next frame.
        if (!isTouchDevice && focusZone != FocusZone.RESULTS) runCatching { searchFocusRequester.requestFocus() }
        suppressSelectUntilMs = SystemClock.elapsedRealtime() + SEARCH_SELECT_SUPPRESS_MS
    }
    LaunchedEffect(isSearchEditing, searchEditRequestNonce) {
        if (isSearchEditing) {
            runCatching { textInputFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }
    // A keyboard that closes itself takes its BACK press with it, so the screen is never told
    // it is gone and typing mode outlives it — every direction key then goes to an input that
    // is no longer there. Follow what the keyboard actually does instead (SearchEditingEntry).
    val imeVisible = WindowInsets.isImeVisible
    var keyboardWasSeen by remember { mutableStateOf(false) }
    LaunchedEffect(isSearchEditing, imeVisible) {
        if (!isSearchEditing) {
            keyboardWasSeen = false
            return@LaunchedEffect
        }
        if (imeVisible) keyboardWasSeen = true
        if (!searchEditingSurvivesKeyboard(imeVisible, keyboardWasSeen)) {
            isSearchEditing = false
            runCatching { searchFocusRequester.requestFocus() }
        }
    }
    // Coming back from the background composes nothing anew, so the entry guard above would not
    // run again: re-arm it here and make sure the screen is never resumed in typing mode.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isSearchEditing = false
                keyboardController?.hide()
                suppressSelectUntilMs = SystemClock.elapsedRealtime() + SEARCH_SELECT_SUPPRESS_MS
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Every door into typing mode goes through here: the D-pad handler below, select on the
    // search bar itself, and its click. A press that still belongs to the one that OPENED this
    // screen is dropped — see SearchEditingEntry.kt for what it does to the screen otherwise.
    fun startSearchEditing(repeatCount: Int = 0) {
        if (!startsSearchEditing(SystemClock.elapsedRealtime(), suppressSelectUntilMs, repeatCount)) return
        focusZone = FocusZone.SEARCH_INPUT
        isSearchEditing = true
        searchEditRequestNonce++
    }

    val canEnterResults = activeCategories.isNotEmpty()

    BackHandler {
        if (isSearchEditing) {
            isSearchEditing = false
            keyboardController?.hide()
            runCatching { searchFocusRequester.requestFocus() }
        } else {
            when (focusZone) {
                FocusZone.RESULTS -> {
                    focusZone = FocusZone.SEARCH_INPUT
                    runCatching { searchFocusRequester.requestFocus() }
                }
                FocusZone.SEARCH_INPUT -> {
                    focusZone = FocusZone.SIDEBAR
                }
                FocusZone.SIDEBAR -> {
                    onBack()
                }
            }
        }
    }

    // One D-pad handler owns zone transitions.
    val dpadModifier = if (!isTouchDevice) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && consumedDpadKey == event.key) {
                consumedDpadKey = null
                return@onPreviewKeyEvent true
            }
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (isSearchEditing && (event.key == Key.Back || event.key == Key.Escape)) {
                isSearchEditing = false
                keyboardController?.hide()
                runCatching { searchFocusRequester.requestFocus() }
                consumedDpadKey = event.key
                return@onPreviewKeyEvent true
            }
            if (isSearchEditing) return@onPreviewKeyEvent false
            val effectiveKey = when (event.key) {
                Key.DirectionLeft  -> if (isRtl) Key.DirectionRight else Key.DirectionLeft
                Key.DirectionRight -> if (isRtl) Key.DirectionLeft  else Key.DirectionRight
                else -> event.key
            }
            val handled = when (effectiveKey) {
                Key.Back, Key.Escape -> when (focusZone) {
                    FocusZone.RESULTS -> { focusZone = FocusZone.SEARCH_INPUT; searchFocusRequester.requestFocus(); true }
                    FocusZone.SEARCH_INPUT -> {
                        // Always progress toward sidebar so repeated Back presses can exit Search.
                        isSearchEditing = false
                        keyboardController?.hide()
                        focusZone = FocusZone.SIDEBAR
                        true
                    }
                    FocusZone.SIDEBAR -> { onBack(); true }
                }
                Key.DirectionUp -> when (focusZone) {
                    FocusZone.SIDEBAR -> true
                    FocusZone.SEARCH_INPUT -> {
                        isSearchEditing = false
                        keyboardController?.hide()
                        focusZone = FocusZone.SIDEBAR
                        true
                    }
                    FocusZone.RESULTS -> {
                        if (currentRowIndex > 0) moveResultRow(-1)
                        else { focusZone = FocusZone.SEARCH_INPUT; searchFocusRequester.requestFocus() }
                        true
                    }
                }
                Key.DirectionDown -> when (focusZone) {
                    FocusZone.SIDEBAR -> { focusZone = FocusZone.SEARCH_INPUT; searchFocusRequester.requestFocus(); true }
                    FocusZone.SEARCH_INPUT -> {
                        isSearchEditing = false
                        keyboardController?.hide()
                        if (canEnterResults) {
                            resultsLastNavEventTime = SystemClock.elapsedRealtime()
                            focusZone = FocusZone.RESULTS
                        } else if (uiState.isLoading) {
                            enterResultsOnLoad = true
                        }
                        true
                    }
                    FocusZone.RESULTS -> {
                        if (currentRowIndex < activeCategories.size - 1) moveResultRow(1)
                        true
                    }
                }
                Key.DirectionLeft -> when (focusZone) {
                    FocusZone.SIDEBAR -> { if (sidebarFocusIndex > 0) sidebarFocusIndex--; true }
                    FocusZone.RESULTS -> {
                        if (currentItemIndex > 0) {
                            resultsLastNavEventTime = SystemClock.elapsedRealtime()
                            currentItemIndex--
                        }
                        true
                    }
                    else -> false
                }
                Key.DirectionRight -> when (focusZone) {
                    FocusZone.SIDEBAR -> { if (sidebarFocusIndex < maxSidebarIndex) sidebarFocusIndex++; true }
                    FocusZone.RESULTS -> {
                        val cats = activeCategories.filter { it.items.isNotEmpty() }
                        val maxItem = (cats.getOrNull(currentRowIndex)?.items?.size ?: 1) - 1
                        if (currentItemIndex < maxItem) {
                            resultsLastNavEventTime = SystemClock.elapsedRealtime()
                            currentItemIndex++
                        }
                        true
                    }
                    else -> false
                }
                Key.Enter, Key.DirectionCenter -> {
                    when (focusZone) {
                        FocusZone.SIDEBAR -> {
                            if (hasProfile && sidebarFocusIndex == 0) onSwitchProfile()
                            else when (topBarFocusedItem(sidebarFocusIndex, hasProfile)) {
                                SidebarItem.HOME -> onNavigateToHome()
                                SidebarItem.SETTINGS -> onNavigateToSettings()
                                else -> Unit
                            }
                            true
                        }
                        FocusZone.SEARCH_INPUT -> {
                            startSearchEditing(event.nativeKeyEvent.repeatCount)
                            true
                        }
                        FocusZone.RESULTS -> {
                            // Use stable category lookup to avoid race condition with dynamic list updates
                            val cats = activeCategories.filter { it.items.isNotEmpty() }
                            val item = cats.getOrNull(currentRowIndex)?.items?.getOrNull(currentItemIndex)
                            if (item != null) onNavigateToDetails(item.mediaType, item.id)
                            true
                        }
                    }
                }
                else -> false
            }
            if (handled) consumedDpadKey = event.key
            handled
        }
    } else Modifier

    Box(modifier = Modifier.fillMaxSize().background(appBackgroundDark()).then(dpadModifier).testTag("search-screen")) {
        if (!isTouchDevice) AppTopBar(selectedItem = SidebarItem.SEARCH, isFocused = focusZone == FocusZone.SIDEBAR, focusedIndex = sidebarFocusIndex, profile = currentProfile)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isTouchDevice) Modifier.statusBarsPadding().padding(top = 12.dp)
                    else Modifier.padding(top = AppTopBarContentTopInset)
                )
                .padding(horizontal = if (isTouchDevice) 0.dp else if (isCompactHeight) 20.dp else 28.dp)
        ) {
            // ── Search Bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isTouchDevice) 16.dp else 22.dp,
                        end = if (isTouchDevice) 16.dp else 22.dp,
                        bottom = if (isCompactHeight) 6.dp else 8.dp
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchInputBar(
                    query = uiState.query,
                    searchBarWidth = searchBarWidth,
                    isTouchDevice = isTouchDevice,
                    isFocused = focusZone == FocusZone.SEARCH_INPUT || isSearchEditing,
                    isEditing = isSearchEditing,
                    searchFocusRequester = searchFocusRequester,
                    textInputFocusRequester = textInputFocusRequester,
                    onQueryChange = { enterResultsOnLoad = false; viewModel.updateQuery(it) },
                    onSearch = {
                        viewModel.search()
                        keyboardController?.hide()
                        isSearchEditing = false
                        enterResultsOnLoad = !isTouchDevice
                    },
                    onFocused = {
                        if (focusZone == FocusZone.SEARCH_INPUT) {
                            isSearchInputFocused = true
                        }
                    },
                    onFocusLost = { isSearchInputFocused = false },
                    onStartEditing = { repeatCount -> startSearchEditing(repeatCount) },
                    onMoveUp = {
                        isSearchEditing = false
                        keyboardController?.hide()
                        focusZone = FocusZone.SIDEBAR
                    },
                    onMoveDown = {
                        isSearchEditing = false
                        keyboardController?.hide()
                        if (canEnterResults) {
                            resultsLastNavEventTime = SystemClock.elapsedRealtime()
                            focusZone = FocusZone.RESULTS
                        }
                    }
                )
            }

            // ── Content ──
            when {
                uiState.isLoading && !hasSearchResults -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator(color = Pink, size = 48.dp) }

                uiState.query.isNotEmpty() && !hasSearchResults -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (uiState.error != null) stringResource(R.string.error_loading)
                            else "${stringResource(R.string.no_results_for)} \"${uiState.query}\"",
                            style = ArflixTypography.body, color = TextSecondary)
                    }
                }

                uiState.isDiscoverLoading && activeCategories.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator(color = Pink, size = 48.dp) }

                activeCategories.isNotEmpty() -> {
                    // Row-based content (library rows or search results) - HomeScreen pattern
                    key(uiState.query) { RowsLayer(
                        categories = activeCategories,
                        cardLogoUrls = activeLogoUrls,
                        currentRowIndex = currentRowIndex,
                        currentItemIndex = currentItemIndex,
                        lastNavEventTime = resultsLastNavEventTime,
                        fastScrollThresholdMs = fastScrollThresholdMs,
                        isFocused = focusZone == FocusZone.RESULTS,
                        isTouchDevice = isTouchDevice,
                        modifier = if (isTouchDevice) Modifier else Modifier.focusRequester(resultsFocusRequester).focusable(),
                        onItemClick = { onNavigateToDetails(it.mediaType, it.id) }
                    ) }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchInputBar(
    query: String,
    searchBarWidth: Dp,
    isTouchDevice: Boolean,
    isFocused: Boolean,
    isEditing: Boolean,
    searchFocusRequester: FocusRequester,
    textInputFocusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onFocused: () -> Unit,
    onFocusLost: () -> Unit,
    onStartEditing: (Int) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    if (isTouchDevice) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.search), style = ArflixTypography.body, color = TextSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = if (isFocused) Pink else TextSecondary, modifier = Modifier.size(22.dp)) },
            textStyle = ArflixTypography.body.copy(color = TextPrimary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = TextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Color.White,
                focusedContainerColor = BackgroundCard,
                unfocusedContainerColor = BackgroundCard,
                focusedIndicatorColor = Color.White,
                unfocusedIndicatorColor = Color.White.copy(alpha = 0.18f)
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search-input")
                .focusRequester(searchFocusRequester)
                .onFocusChanged {
                    if (it.isFocused) onFocused() else onFocusLost()
                }
        )
        return
    }

    val shape = rememberArvioCardShape(10.dp)
    ArvioFocusableSurface(
        modifier = Modifier
            .width(searchBarWidth)
            .height(66.dp)
            .onPreviewKeyEvent { event ->
                if (!isFocused || isEditing) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { onMoveUp(); true }
                    Key.DirectionDown -> { onMoveDown(); true }
                    Key.Enter, Key.DirectionCenter -> { onStartEditing(event.nativeKeyEvent.repeatCount); true }
                    else -> false
                }
            }
            .focusRequester(searchFocusRequester),
        shape = shape,
        backgroundColor = Color.White.copy(alpha = if (isFocused) 0.075f else 0.045f),
        outlineColor = Color.White,
        outlineWidth = if (isFocused) 3.dp else 2.dp,
        glowWidth = if (isFocused) 2.dp else 0.dp,
        glowAlpha = 0.22f,
        focusedScale = 1f,
        pressedScale = 0.985f,
        useSystemFocusForVisuals = false,
        isFocusedOverride = isFocused,
        onClick = { onStartEditing(0) },
        onFocusChanged = { if (it) onFocused() else onFocusLost() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = if (isFocused) Color.White else TextSecondary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                readOnly = !isEditing,
                textStyle = ArflixTypography.body.copy(color = TextPrimary, fontSize = 20.sp),
                cursorBrush = SolidColor(Color.White),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("search-input")
                    .focusRequester(textInputFocusRequester),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.search),
                            style = ArflixTypography.body.copy(fontSize = 20.sp),
                            color = Color.White.copy(alpha = 0.32f)
                        )
                    }
                    inner()
                }
            )
        }
    }
}

// ── Rows Layer (HomeScreen pattern - manual focus, smooth scroll) ────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RowsLayer(
    categories: List<Category>, cardLogoUrls: Map<String, String>,
    currentRowIndex: Int, currentItemIndex: Int,
    lastNavEventTime: Long,
    fastScrollThresholdMs: Long,
    isFocused: Boolean,
    isTouchDevice: Boolean,
    modifier: Modifier = Modifier,
    onItemClick: (MediaItem) -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeight = configuration.screenHeightDp

    val focusBleedPadding = if (isTouchDevice) 16.dp else 22.dp

    val listState = rememberLazyListState()
    var lastAppliedTargetIndex by remember { mutableIntStateOf(-1) }
    val targetIndex = currentRowIndex.coerceIn(0, (categories.size - 1).coerceAtLeast(0))

    // Only move the results viewport in response to actual D-pad navigation on TV.
    if (!isTouchDevice) {
        LaunchedEffect(targetIndex, isFocused) {
            val currentFirst = listState.firstVisibleItemIndex
            val initialPlacement = lastAppliedTargetIndex < 0
            if (currentFirst == targetIndex) {
                lastAppliedTargetIndex = targetIndex
                return@LaunchedEffect
            }

            val recentUserNav = lastNavEventTime > 0L &&
                (SystemClock.elapsedRealtime() - lastNavEventTime) <= fastScrollThresholdMs
            if (!initialPlacement && !recentUserNav) return@LaunchedEffect

            val jump = kotlin.math.abs(targetIndex - currentFirst)
            if (!initialPlacement && jump <= 5) {
                listState.animateScrollToItem(targetIndex)
            } else {
                listState.scrollToItem(targetIndex)
            }
            lastAppliedTargetIndex = targetIndex
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = if (isTouchDevice) 4.dp else focusBleedPadding / 2,
                bottom = if (isTouchDevice) 24.dp + LocalBottomBarInset.current else maxHeight * 0.6f
            ),
            modifier = Modifier.fillMaxSize().arvioDpadFocusGroup(),
            verticalArrangement = Arrangement.spacedBy(if (isTouchDevice) 20.dp else 0.dp)
        ) {
            items(categories.size, key = { categories[it].id }) { index ->
                val category = categories[index]
                val isCurrentRow = !isTouchDevice && isFocused && index == currentRowIndex
                val rowKey = remember(category.id) { "search:${category.id}" }
                val rowUsePosterCards = rememberCatalogueRowLayoutMode(rowKey) == CardLayoutMode.POSTER
                val isPortrait = rowUsePosterCards
                val itemWidth = if (isTouchDevice) {
                    if (isPortrait) 120.dp else 200.dp
                } else {
                    if (isPortrait) 105.dp else 210.dp
                }
                val baseRowHeight = if (isPortrait) {
                    // Poster cards (2:3) need extra vertical room for title + date below the image
                    if (screenHeight <= 640) 271.dp else 309.dp
                } else {
                    // Landscape cards still render title + subtitle below artwork.
                    if (screenHeight <= 640) 210.dp else 274.dp
                }
                val rowHeight = baseRowHeight + focusBleedPadding
                // Fade non-current rows on TV only
                val rowAlpha by animateFloatAsState(
                    targetValue = if (isTouchDevice || !isFocused || index <= currentRowIndex) 1f else 0.3f,
                    animationSpec = tween(250), label = "rowAlpha"
                )

                val rowContent = @Composable {
                    Column(modifier = Modifier.fillMaxWidth().testTag("search-row-${category.id}")) {
                        Row(
                            modifier = Modifier.padding(
                                start = if (isTouchDevice) 16.dp else focusBleedPadding,
                                bottom = if (isTouchDevice) 4.dp else 4.dp,
                                top = if (isTouchDevice) 0.dp else 4.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                localizedRowTitle(category),
                                style = if (isTouchDevice) {
                                    ArflixTypography.sectionTitle.copy(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        shadow = Shadow(
                                            color = Color.Black.copy(alpha = 0.8f),
                                            offset = Offset(1f, 1f),
                                            blurRadius = 4f
                                        )
                                    )
                                } else {
                                    ArvioSkin.typography.sectionTitle.copy(fontSize = 15.sp)
                                },
                                color = if (isTouchDevice) Color.White else Color.White.copy(alpha = if (isCurrentRow) 0.9f else 0.5f)
                            )
                        }

                        val rowState = rememberLazyListState()
                        // Keep visible cards still; only scroll enough to reveal a clipped selection.
                        if (!isTouchDevice) {
                            LaunchedEffect(isCurrentRow, currentItemIndex) {
                                if (!isCurrentRow) return@LaunchedEffect
                                val safeIndex = currentItemIndex.coerceIn(0, (category.items.size - 1).coerceAtLeast(0))
                                val first = rowState.firstVisibleItemIndex
                                val visibleItems = rowState.layoutInfo.visibleItemsInfo
                                val targetInfo = visibleItems.firstOrNull { it.index == safeIndex }
                                if (targetInfo == null) {
                                    if (kotlin.math.abs(safeIndex - first) > 6) rowState.scrollToItem(safeIndex)
                                    else rowState.animateScrollToItem(safeIndex)
                                } else {
                                    val margin = with(density) { focusBleedPadding.roundToPx() }
                                    val end = rowState.layoutInfo.viewportEndOffset - margin
                                    val delta = when {
                                        targetInfo.offset < 0 -> targetInfo.offset
                                        targetInfo.offset + targetInfo.size > end -> targetInfo.offset + targetInfo.size - end
                                        else -> 0
                                    }
                                    if (delta != 0) rowState.animateScrollBy(delta.toFloat())
                                }
                            }
                        }

                        LazyRow(
                            state = rowState,
                            modifier = Modifier.arvioDpadFocusGroup(),
                            contentPadding = if (isTouchDevice) {
                                PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
                            } else {
                                PaddingValues(
                                    start = focusBleedPadding,
                                    end = itemWidth + 56.dp,
                                    top = 8.dp,
                                    bottom = focusBleedPadding + 12.dp
                                )
                            },
                            horizontalArrangement = Arrangement.spacedBy(if (isTouchDevice) 14.dp else 18.dp)
                        ) {
                            itemsIndexed(category.items, key = { _, item -> "${item.mediaType}_${item.id}" }) { itemIdx, item ->
                                val itemIsFocused = !isTouchDevice && isCurrentRow && itemIdx == currentItemIndex
                                MediaCard(
                                    item = item.copy(
                                        title = buildCardTitle(item),
                                        subtitle = buildCardSubtitle(item),
                                        releaseDate = null,
                                        year = ""
                                    ),
                                    width = itemWidth,
                                    isLandscape = !isPortrait,
                                    logoImageUrl = cardLogoUrls["${item.mediaType}_${item.id}"],
                                    showProgress = false,
                                    titleMaxLines = 2,
                                    subtitleMaxLines = 1,
                                    isFocusedOverride = itemIsFocused,
                                    enableSystemFocus = false,
                                    onFocused = {},
                                    onClick = { onItemClick(item) },
                                    modifier = Modifier.testTag("search-card-${category.id}-${item.mediaType}-${item.id}")
                                        .semantics { selected = itemIsFocused }
                                        .then(if (isTouchDevice) Modifier.clickable { onItemClick(item) } else Modifier)
                                )
                            }
                        }
                    }
                }

                if (isTouchDevice) {
                    rowContent()
                } else {
                    Box(modifier = Modifier.fillMaxWidth().height(rowHeight).graphicsLayer { alpha = rowAlpha }) {
                        rowContent()
                    }
                }
            }
        }
    }
}

private fun buildCardTitle(item: MediaItem): String {
    // Return the clean title — year is shown separately in the subtitle
    return item.title
}

@Composable
private fun buildCardSubtitle(item: MediaItem): String {
    val mediaLabel = when (item.mediaType) {
        MediaType.TV -> stringResource(R.string.series)
        MediaType.MOVIE -> stringResource(R.string.movie)
    }
    val year = item.year.takeIf { it.isNotBlank() }
    return if (year != null) "$mediaLabel · $year" else mediaLabel
}

private enum class FocusZone { SIDEBAR, SEARCH_INPUT, RESULTS }
