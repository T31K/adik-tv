package com.arflix.tv.ui.screens.settings

import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.widget.doAfterTextChanged
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.BuildConfig
import com.arflix.tv.R
import com.arflix.tv.data.model.DownloadStatus
import com.arflix.tv.ui.components.AboutCreditsDialog
import com.arflix.tv.ui.components.AppTopBar
import com.arflix.tv.ui.components.AppTopBarContentTopInset
import com.arflix.tv.ui.components.AppUpdateModal
import com.arflix.tv.ui.components.SettingsRow
import com.arflix.tv.ui.components.SettingsToggleRow
import com.arflix.tv.ui.components.SidebarItem
import com.arflix.tv.ui.components.Toast
import com.arflix.tv.ui.components.topBarFocusedItem
import com.arflix.tv.ui.components.topBarMaxIndex
import com.arflix.tv.ui.focus.arvioDpadFocusGroup
import com.arflix.tv.ui.focus.focusRevealDelta
import com.arflix.tv.ui.focus.isArvioDpadNavigationKey
import com.arflix.tv.ui.focus.rememberArvioDpadRepeatGate
import com.arflix.tv.ui.skin.resolveAccentColor
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.BackgroundElevated
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.SuccessGreen
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.updater.UpdateStatus
import com.arflix.tv.util.LocalDeviceType

/**
 * Registry of the focusable rows of one settings section, keyed by focus index.
 * On TV the screen scrolls by the measured row bounds ([revealDelta]); on touch
 * devices rows bring themselves into view through [requesters].
 */
@OptIn(ExperimentalFoundationApi::class)
class SettingsFocusTracker {
    val requesters = mutableMapOf<Int, BringIntoViewRequester>()
    val coordinates = mutableMapOf<Int, LayoutCoordinates>()
    var viewport: LayoutCoordinates? = null

    fun revealDelta(index: Int): Float? {
        val parent = viewport?.takeIf { it.isAttached } ?: return null
        val child = coordinates[index]?.takeIf { it.isAttached } ?: return null
        return runCatching {
            val bounds = parent.localBoundingBoxOf(child, clipBounds = false)
            focusRevealDelta(bounds.top, bounds.bottom, parent.size.height.toFloat())
        }.getOrNull()
    }
}

val LocalSettingsFocusTracker = compositionLocalOf<SettingsFocusTracker?> { null }

/**
 * Attach to the outermost modifier of a focusable settings row so the scroll
 * container keeps it in view when it gains focus. [index] is the row's focus index.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.settingsFocusSlot(index: Int): Modifier {
    val tracker = LocalSettingsFocusTracker.current ?: return this
    if (!LocalDeviceType.current.isTouchDevice()) {
        val owner = remember(tracker, index) { arrayOfNulls<LayoutCoordinates>(1) }
        DisposableEffect(tracker, index) {
            onDispose { if (tracker.coordinates[index] === owner[0]) tracker.coordinates.remove(index) }
        }
        return this.onGloballyPositioned {
            owner[0] = it
            tracker.coordinates[index] = it
        }
    }
    val requester = remember(index) { BringIntoViewRequester() }
    DisposableEffect(tracker, index, requester) {
        tracker.requesters[index] = requester
        onDispose {
            if (tracker.requesters[index] === requester) tracker.requesters.remove(index)
        }
    }
    return this.bringIntoViewRequester(requester)
}

/** App UI / TMDB metadata languages as (tag, display name). */
val TMDB_LANGUAGES = listOf(
    "af-ZA" to "Afrikaans",
    "sq-AL" to "Albanian (Shqip)",
    "ar-SA" to "Arabic",
    "eu-ES" to "Basque (Euskara)",
    "bn-BD" to "Bengali",
    "bg-BG" to "Bulgarian",
    "ca-ES" to "Catalan",
    "zh-CN" to "Chinese (Simplified)",
    "zh-TW" to "Chinese (Traditional)",
    "hr-HR" to "Croatian (Hrvatski)",
    "cs-CZ" to "Czech (Cesky)",
    "da-DK" to "Danish (Dansk)",
    "nl-NL" to "Dutch (Nederlands)",
    "en-US" to "English",
    "et-EE" to "Estonian",
    "tl-PH" to "Filipino/Tagalog",
    "fi-FI" to "Finnish (Suomi)",
    "fr-FR" to "French (Francais)",
    "gl-ES" to "Galician (Galego)",
    "de-DE" to "German (Deutsch)",
    "el-GR" to "Greek",
    "gu-IN" to "Gujarati",
    "he-IL" to "Hebrew",
    "hi-IN" to "Hindi",
    "hu-HU" to "Hungarian (Magyar)",
    "id-ID" to "Indonesian",
    "it-IT" to "Italian (Italiano)",
    "ja-JP" to "Japanese",
    "kn-IN" to "Kannada",
    "ko-KR" to "Korean",
    "lv-LV" to "Latvian",
    "lt-LT" to "Lithuanian",
    "ms-MY" to "Malay",
    "ml-IN" to "Malayalam",
    "mr-IN" to "Marathi",
    "no-NO" to "Norwegian (Norsk)",
    "fa-IR" to "Persian (Farsi)",
    "pl-PL" to "Polish (Polski)",
    "pt-PT" to "Portuguese (Portugues)",
    "pt-BR" to "Português (Brasil)",
    "pa-IN" to "Punjabi",
    "ro-RO" to "Romanian (Romana)",
    "ru-RU" to "Russian",
    "sr-RS" to "Serbian (Srpski)",
    "sk-SK" to "Slovak (Slovensky)",
    "sl-SI" to "Slovenian (Slovenscina)",
    "es-ES" to "Spanish (Espanol)",
    "sw-KE" to "Swahili",
    "sv-SE" to "Swedish (Svenska)",
    "ta-IN" to "Tamil",
    "te-IN" to "Telugu",
    "th-TH" to "Thai",
    "tr-TR" to "Turkish (Turkce)",
    "uk-UA" to "Ukrainian",
    "ur-PK" to "Urdu",
    "vi-VN" to "Vietnamese"
)

private val SECTIONS = listOf("accounts", "downloads", "subtitles", "language", "appearance", "profiles")

private enum class Zone { SIDEBAR, SECTION, CONTENT }

private enum class Picker { APP_LANGUAGE, AUDIO_LANGUAGE, SUBTITLE_LANGUAGE }

/** One focusable row of a non-download section. */
private sealed class SettingsItem(val onActivate: () -> Unit) {
    class Value(
        val icon: ImageVector,
        val title: String,
        val subtitle: String,
        val value: String,
        onActivate: () -> Unit
    ) : SettingsItem(onActivate)

    class Toggle(
        val title: String,
        val subtitle: String,
        val checked: Boolean,
        val onToggle: (Boolean) -> Unit
    ) : SettingsItem({ onToggle(!checked) })

    class Action(
        val title: String,
        val description: String,
        val actionLabel: String,
        onActivate: () -> Unit
    ) : SettingsItem(onActivate)
}

@Composable
private fun SettingsMessage.localizedText(): String = when (this) {
    is SettingsMessage.Raw -> text
    is SettingsMessage.Res -> stringResource(resourceId, *formatArgs.toTypedArray())
}

private fun subtitleColorLabel(rgb: Int): String =
    SettingsViewModel.SUBTITLE_COLORS.firstOrNull { it.second == rgb }?.first
        ?: "#%06X".format(rgb and 0xFFFFFF)

private fun playerLanguageLabel(code: String): String =
    SettingsViewModel.PLAYER_LANGUAGES.firstOrNull { it.first.equals(code, ignoreCase = true) }?.second
        ?: code.uppercase()

/**
 * Settings screen: a tab rail (Accounts, Downloads, Subtitles, Language, Appearance,
 * Profiles) and the rows of the selected tab. D-pad focus is managed by hand across
 * three zones: the top bar, the tab rail and the content rows.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    currentProfile: com.arflix.tv.data.model.Profile? = null,
    initialSection: String? = null,
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val hasProfile = currentProfile != null
    val maxSidebarIndex = topBarMaxIndex(hasProfile)
    var sidebarFocusIndex by remember { mutableIntStateOf(maxSidebarIndex) } // settings gear
    var sectionIndex by remember {
        mutableIntStateOf(initialSection?.let { SECTIONS.indexOf(it) }?.takeIf { it >= 0 } ?: 0)
    }
    var activeZone by remember { mutableStateOf(Zone.SECTION) }
    var contentFocusIndex by remember { mutableIntStateOf(0) }
    var suppressSelectUntilMs by remember { mutableLongStateOf(0L) }

    var showCredits by remember { mutableStateOf(false) }
    var showDeviceIdInput by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var downloadActionTarget by remember { mutableStateOf<DownloadItemUi?>(null) }

    val section = SECTIONS[sectionIndex]
    val items = settingsItems(
        section = section,
        uiState = uiState,
        viewModel = viewModel,
        onEditDeviceId = { showDeviceIdInput = true },
        onOpenCredits = { showCredits = true },
        onOpenPicker = { picker = it },
        onSwitchProfile = onSwitchProfile
    )
    val downloadRows = uiState.downloads.rows
    val contentMaxIndex = if (section == "downloads") downloadRows.lastIndex else items.lastIndex

    val hasBlockingModal = showCredits ||
        showDeviceIdInput ||
        picker != null ||
        downloadActionTarget != null ||
        uiState.showAppUpdateDialog ||
        uiState.showUnknownSourcesDialog

    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val focusTracker = remember(sectionIndex) { SettingsFocusTracker() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        suppressSelectUntilMs = SystemClock.elapsedRealtime() + 150L
    }

    // Dialogs live in their own windows; hand D-pad input back to the screen when they close.
    LaunchedEffect(hasBlockingModal) {
        if (!hasBlockingModal) runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(sectionIndex) {
        if (scrollState.value != 0) scrollState.scrollTo(0)
    }

    // The download list changes under the focus; keep the focus on a real row.
    LaunchedEffect(contentMaxIndex) {
        if (contentFocusIndex > contentMaxIndex) {
            contentFocusIndex = contentMaxIndex.coerceAtLeast(0)
        }
        if (contentMaxIndex < 0 && activeZone == Zone.CONTENT) activeZone = Zone.SECTION
    }

    LaunchedEffect(contentFocusIndex, focusTracker, activeZone) {
        if (activeZone != Zone.CONTENT) return@LaunchedEffect
        withFrameNanos { }
        focusTracker.revealDelta(contentFocusIndex)?.let { delta ->
            val target = (scrollState.value + delta).toInt().coerceIn(0, scrollState.maxValue)
            if (target != scrollState.value) {
                scrollState.animateScrollTo(target, tween(100, easing = FastOutSlowInEasing))
            }
            return@LaunchedEffect
        }
        focusTracker.requesters[contentFocusIndex]?.let { runCatching { it.bringIntoView() } }
    }

    val dpadRepeatGate = rememberArvioDpadRepeatGate(
        horizontalMinRepeatIntervalMs = 80L,
        verticalMinRepeatIntervalMs = 112L
    )

    val selectSection: (Int) -> Unit = { index ->
        sectionIndex = index
        contentFocusIndex = 0
    }

    // BackHandler is the only owner of Back (the key itself is never consumed below),
    // so one press always takes exactly one step.
    val goBack: () -> Unit = {
        when (activeZone) {
            Zone.CONTENT -> activeZone = Zone.SECTION
            Zone.SECTION, Zone.SIDEBAR -> onBack()
        }
    }
    BackHandler(enabled = !hasBlockingModal) { goBack() }

    val activateContent: () -> Unit = {
        if (section == "downloads") {
            val item = downloadRows.getOrNull(contentFocusIndex)
            // A finished download waiting to be moved onto the drive has no actions.
            if (item != null && uiState.downloads.moving.none { it.id == item.id }) {
                downloadActionTarget = item
            }
        } else {
            items.getOrNull(contentFocusIndex)?.onActivate?.invoke()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundDark())
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (hasBlockingModal) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp && isArvioDpadNavigationKey(event.key)) {
                    dpadRepeatGate.reset()
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (isArvioDpadNavigationKey(event.key) &&
                    dpadRepeatGate.shouldSkip(
                        keyCode = event.nativeKeyEvent.keyCode,
                        repeatCount = event.nativeKeyEvent.repeatCount,
                        nowMs = SystemClock.elapsedRealtime()
                    )
                ) {
                    return@onPreviewKeyEvent true
                }
                val key = when {
                    isRtl && event.key == Key.DirectionLeft -> Key.DirectionRight
                    isRtl && event.key == Key.DirectionRight -> Key.DirectionLeft
                    else -> event.key
                }
                when (key) {
                    // Key.Back deliberately absent: BackHandler owns it.
                    Key.Escape -> { goBack(); true }
                    Key.DirectionLeft -> {
                        when (activeZone) {
                            Zone.SIDEBAR -> if (sidebarFocusIndex > 0) sidebarFocusIndex--
                            Zone.SECTION -> Unit
                            Zone.CONTENT -> activeZone = Zone.SECTION
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        when (activeZone) {
                            Zone.SIDEBAR -> if (sidebarFocusIndex < maxSidebarIndex) sidebarFocusIndex++
                            Zone.SECTION -> if (contentMaxIndex >= 0) activeZone = Zone.CONTENT
                            Zone.CONTENT -> Unit
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        when (activeZone) {
                            Zone.SIDEBAR -> Unit
                            Zone.SECTION -> if (sectionIndex > 0) selectSection(sectionIndex - 1) else activeZone = Zone.SIDEBAR
                            Zone.CONTENT -> if (contentFocusIndex > 0) contentFocusIndex-- else activeZone = Zone.SECTION
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        when (activeZone) {
                            Zone.SIDEBAR -> activeZone = Zone.SECTION
                            Zone.SECTION -> if (sectionIndex < SECTIONS.lastIndex) selectSection(sectionIndex + 1)
                            Zone.CONTENT -> if (contentFocusIndex < contentMaxIndex) contentFocusIndex++
                        }
                        true
                    }
                    Key.Enter, Key.DirectionCenter -> {
                        if (SystemClock.elapsedRealtime() < suppressSelectUntilMs) return@onPreviewKeyEvent true
                        when (activeZone) {
                            Zone.SIDEBAR -> {
                                if (hasProfile && sidebarFocusIndex == 0) {
                                    onSwitchProfile()
                                } else {
                                    when (topBarFocusedItem(sidebarFocusIndex, hasProfile)) {
                                        SidebarItem.SEARCH -> onNavigateToSearch()
                                        SidebarItem.HOME -> onNavigateToHome()
                                        else -> Unit
                                    }
                                }
                            }
                            Zone.SECTION -> if (contentMaxIndex >= 0) activeZone = Zone.CONTENT
                            Zone.CONTENT -> activateContent()
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        AppTopBar(
            selectedItem = SidebarItem.SETTINGS,
            isFocused = activeZone == Zone.SIDEBAR,
            focusedIndex = sidebarFocusIndex,
            profile = currentProfile,
            clockFormat = uiState.clockFormat
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = AppTopBarContentTopInset)
                .padding(start = 34.dp, end = 42.dp, bottom = 28.dp)
        ) {
            // Tab rail
            Column(
                modifier = Modifier
                    .width(204.dp)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF050505))
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
                    .padding(vertical = 18.dp, horizontal = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings),
                    style = ArflixTypography.cardTitle.copy(fontSize = 19.sp),
                    color = TextPrimary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .padding(bottom = 14.dp)
                )
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SECTIONS.forEachIndexed { index, id ->
                        SettingsSectionItem(
                            icon = sectionIcon(id),
                            title = sectionTitle(id),
                            isSelected = sectionIndex == index,
                            isFocused = activeZone == Zone.SECTION && sectionIndex == index,
                            onClick = {
                                selectSection(index)
                                activeZone = Zone.SECTION
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "ADIK TV v${BuildConfig.VERSION_NAME}",
                    style = ArflixTypography.caption,
                    color = TextSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .onGloballyPositioned { focusTracker.viewport = it }
                    .verticalScroll(scrollState)
                    .padding(start = 28.dp)
            ) {
                SettingsSectionHeader(section)
                Spacer(modifier = Modifier.height(12.dp))
                val focusedIndex = if (activeZone == Zone.CONTENT) contentFocusIndex else -1
                CompositionLocalProvider(LocalSettingsFocusTracker provides focusTracker) {
                    if (section == "downloads") {
                        DownloadsSettings(
                            downloads = uiState.downloads,
                            focusedIndex = focusedIndex,
                            onRowClick = { index ->
                                contentFocusIndex = index
                                activeZone = Zone.CONTENT
                                activateContent()
                            }
                        )
                    } else {
                        SettingsItemRows(
                            items = items,
                            focusedIndex = focusedIndex,
                            onRowClick = { index ->
                                contentFocusIndex = index
                                activeZone = Zone.CONTENT
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (showCredits) {
            AboutCreditsDialog { showCredits = false }
        }

        if (showDeviceIdInput) {
            DeviceIdInputModal(
                initialValue = uiState.megaflixDeviceId.orEmpty(),
                onConfirm = { value ->
                    viewModel.setMegaflixDeviceId(value)
                    showDeviceIdInput = false
                },
                onDismiss = { showDeviceIdInput = false }
            )
        }

        when (picker) {
            Picker.APP_LANGUAGE -> OptionPickerModal(
                title = stringResource(R.string.app_language),
                options = TMDB_LANGUAGES.map { it.second },
                selectedIndex = TMDB_LANGUAGES.indexOfFirst { it.first == uiState.contentLanguage },
                onSelect = { index ->
                    TMDB_LANGUAGES.getOrNull(index)?.let { viewModel.setContentLanguage(it.first) }
                    picker = null
                },
                onDismiss = { picker = null }
            )
            Picker.AUDIO_LANGUAGE -> OptionPickerModal(
                title = stringResource(R.string.default_audio),
                options = SettingsViewModel.PLAYER_LANGUAGES.map { it.second },
                selectedIndex = SettingsViewModel.PLAYER_LANGUAGES.indexOfFirst {
                    it.first.equals(uiState.player.audioLanguage, ignoreCase = true)
                },
                onSelect = { index ->
                    SettingsViewModel.PLAYER_LANGUAGES.getOrNull(index)?.let { viewModel.setAudioLanguage(it.first) }
                    picker = null
                },
                onDismiss = { picker = null }
            )
            Picker.SUBTITLE_LANGUAGE -> {
                // Index 0 = Off; the rest follow PLAYER_LANGUAGES.
                val options = listOf(stringResource(R.string.off)) + SettingsViewModel.PLAYER_LANGUAGES.map { it.second }
                val current = uiState.player.subtitleLanguage
                OptionPickerModal(
                    title = "Subtitles by default",
                    options = options,
                    selectedIndex = if (current.isBlank()) 0 else SettingsViewModel.PLAYER_LANGUAGES.indexOfFirst {
                        it.first.equals(current, ignoreCase = true)
                    }.let { if (it >= 0) it + 1 else -1 },
                    onSelect = { index ->
                        val code = if (index == 0) "" else SettingsViewModel.PLAYER_LANGUAGES.getOrNull(index - 1)?.first.orEmpty()
                        viewModel.setSubtitleLanguage(code)
                        picker = null
                    },
                    onDismiss = { picker = null }
                )
            }
            null -> Unit
        }

        downloadActionTarget?.let { target ->
            DownloadActionsDialog(
                item = target,
                onStart = { viewModel.startDownload(target.id); downloadActionTarget = null },
                onPause = { viewModel.pauseDownload(target.id); downloadActionTarget = null },
                onStop = { viewModel.stopDownload(target.id); downloadActionTarget = null },
                onDismiss = { downloadActionTarget = null }
            )
        }

        if (uiState.showAppUpdateDialog) {
            AppUpdateModal(
                status = uiState.updateStatus,
                onDownload = { viewModel.downloadAppUpdate() },
                onCancelDownload = { viewModel.cancelDownloadAppUpdate() },
                onInstall = { viewModel.installAppUpdateOrRequestPermission() },
                onDismiss = { viewModel.dismissAppUpdateDialog() },
                onIgnore = { viewModel.ignoreAppUpdate() }
            )
        }

        if (uiState.showUnknownSourcesDialog) {
            UnknownSourcesModal(
                onDismiss = { viewModel.dismissAppUpdateDialog() },
                onOpenSettings = { viewModel.openUnknownSourcesSettings() }
            )
        }

        uiState.toastMessage?.let { message ->
            Toast(
                message = message.localizedText(),
                type = uiState.toastType,
                isVisible = true,
                onDismiss = { viewModel.dismissToast() }
            )
        }
    }
}

// ── Section content ─────────────────────────────────────────────────────

@Composable
private fun settingsItems(
    section: String,
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onEditDeviceId: () -> Unit,
    onOpenCredits: () -> Unit,
    onOpenPicker: (Picker) -> Unit,
    onSwitchProfile: () -> Unit
): List<SettingsItem> = when (section) {
    "accounts" -> {
        val status = uiState.updateStatus
        val selfUpdate = uiState.isSelfUpdateSupported
        listOf(
            SettingsItem.Action(
                title = "Device ID",
                description = uiState.megaflixDeviceId?.let { "Identifies this TV to the feed: $it" }
                    ?: "Not set — enter the id for this TV",
                actionLabel = uiState.megaflixDeviceId ?: "SET",
                onActivate = onEditDeviceId
            ),
            when (val s = uiState.streamState) {
                is com.arflix.tv.stream.PairState.Pending -> SettingsItem.Action(
                    title = "Streaming",
                    description = "Waiting for approval · Code ${com.arflix.tv.stream.StreamIds.display(s.code)} · ${s.deviceId}",
                    actionLabel = "CHECK",
                    onActivate = { viewModel.refreshStream() }
                )
                is com.arflix.tv.stream.PairState.Approved -> SettingsItem.Action(
                    title = "Streaming",
                    description = "Connected · ${s.deviceId}",
                    actionLabel = "ON",
                    onActivate = { viewModel.refreshStream() }
                )
                is com.arflix.tv.stream.PairState.Revoked -> SettingsItem.Action(
                    title = "Streaming",
                    description = "Turned off for this TV · ${s.deviceId}",
                    actionLabel = "OFF",
                    onActivate = { viewModel.refreshStream() }
                )
                is com.arflix.tv.stream.PairState.Failed -> SettingsItem.Action(
                    title = "Streaming",
                    description = "Can't reach the stream server right now",
                    actionLabel = "RETRY",
                    onActivate = { viewModel.refreshStream() }
                )
                com.arflix.tv.stream.PairState.Unknown -> SettingsItem.Action(
                    title = "Streaming",
                    description = "Setting up this TV's streaming keys",
                    actionLabel = "CHECK",
                    onActivate = { viewModel.refreshStream() }
                )
            },
            SettingsItem.Action(
                title = stringResource(R.string.app_update),
                description = when {
                    !selfUpdate -> stringResource(R.string.settings_update_managed_play)
                    status is UpdateStatus.ReadyToInstall -> stringResource(R.string.settings_update_ready_install)
                    status is UpdateStatus.Checking -> stringResource(R.string.settings_update_checking)
                    status is UpdateStatus.UpdateAvailable -> stringResource(
                        R.string.settings_update_available_format,
                        status.update.title.ifBlank { status.update.tag }
                    )
                    status is UpdateStatus.Success -> stringResource(R.string.settings_update_latest)
                    else -> stringResource(R.string.settings_update_check_releases)
                },
                actionLabel = when {
                    !selfUpdate -> stringResource(R.string.settings_badge_play)
                    status is UpdateStatus.ReadyToInstall -> stringResource(R.string.settings_badge_install)
                    status is UpdateStatus.Checking -> stringResource(R.string.settings_badge_checking)
                    status is UpdateStatus.UpdateAvailable -> stringResource(R.string.settings_badge_update)
                    else -> stringResource(R.string.settings_badge_check)
                },
                onActivate = {
                    if (status is UpdateStatus.ReadyToInstall) {
                        viewModel.installAppUpdateOrRequestPermission()
                    } else {
                        viewModel.checkForAppUpdates(force = true, showNoUpdateFeedback = true)
                    }
                }
            ),
            SettingsItem.Action(
                title = stringResource(R.string.about_credits),
                description = "ADIK TV v${BuildConfig.VERSION_NAME} · " + stringResource(R.string.about_credits_description),
                actionLabel = stringResource(R.string.settings_badge_open),
                onActivate = onOpenCredits
            )
        )
    }
    "subtitles" -> {
        val player = uiState.player
        listOf(
            SettingsItem.Value(
                icon = Icons.Default.FormatSize,
                title = stringResource(R.string.subtitle_size),
                subtitle = stringResource(R.string.subtitle_size_desc),
                value = "${player.subtitleScalePercent}%",
                onActivate = viewModel::cycleSubtitleSize
            ),
            SettingsItem.Value(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.subtitle_color),
                subtitle = stringResource(R.string.subtitle_color_desc),
                value = subtitleColorLabel(player.subtitleColor),
                onActivate = viewModel::cycleSubtitleColor
            ),
            SettingsItem.Toggle(
                title = "Background box",
                subtitle = "Draw a dark box behind subtitle text",
                checked = player.subtitleBackground,
                onToggle = viewModel::setSubtitleBackground
            ),
            SettingsItem.Value(
                icon = Icons.Default.Subtitles,
                title = "Subtitles by default",
                subtitle = "Turn on subtitles in this language when a movie starts",
                value = if (player.subtitleLanguage.isBlank()) "Off" else playerLanguageLabel(player.subtitleLanguage),
                onActivate = { onOpenPicker(Picker.SUBTITLE_LANGUAGE) }
            )
        )
    }
    "language" -> listOf(
        SettingsItem.Value(
            icon = Icons.Default.Language,
            title = stringResource(R.string.app_language),
            subtitle = stringResource(R.string.app_language_desc),
            value = TMDB_LANGUAGES.firstOrNull { it.first == uiState.contentLanguage }?.second ?: uiState.contentLanguage,
            onActivate = { onOpenPicker(Picker.APP_LANGUAGE) }
        ),
        SettingsItem.Value(
            icon = Icons.Default.VolumeUp,
            title = stringResource(R.string.default_audio),
            subtitle = stringResource(R.string.audio_desc),
            value = playerLanguageLabel(uiState.player.audioLanguage),
            onActivate = { onOpenPicker(Picker.AUDIO_LANGUAGE) }
        )
    )
    "appearance" -> listOf(
        SettingsItem.Value(
            icon = Icons.Default.Widgets,
            title = stringResource(R.string.card_layout),
            subtitle = stringResource(R.string.card_layout_desc),
            value = uiState.cardLayoutMode,
            onActivate = viewModel::toggleCardLayoutMode
        ),
        SettingsItem.Value(
            icon = Icons.Default.Palette,
            title = stringResource(R.string.accent_color),
            subtitle = stringResource(R.string.accent_color_desc),
            value = uiState.accentColor,
            onActivate = viewModel::cycleAccentColor
        ),
        SettingsItem.Toggle(
            title = stringResource(R.string.oled_black_background),
            subtitle = stringResource(R.string.oled_black_background_desc),
            checked = uiState.oledBlackBackground,
            onToggle = viewModel::setOledBlackBackground
        ),
        SettingsItem.Value(
            icon = Icons.Default.Schedule,
            title = stringResource(R.string.clock_format),
            subtitle = stringResource(R.string.clock_format_desc),
            value = if (uiState.clockFormat == "12h") "12-hour" else "24-hour",
            onActivate = viewModel::cycleClockFormat
        ),
        SettingsItem.Toggle(
            title = stringResource(R.string.show_budget),
            subtitle = stringResource(R.string.show_budget_desc),
            checked = uiState.showBudget,
            onToggle = viewModel::setShowBudget
        ),
        SettingsItem.Toggle(
            title = stringResource(R.string.show_episode_ratings),
            subtitle = stringResource(R.string.show_episode_ratings_desc),
            checked = uiState.showEpisodeRatings,
            onToggle = viewModel::setShowEpisodeRatings
        ),
        SettingsItem.Toggle(
            title = stringResource(R.string.spoiler_blur),
            subtitle = stringResource(R.string.spoiler_blur_desc),
            checked = uiState.spoilerBlurEnabled,
            onToggle = viewModel::setSpoilerBlurEnabled
        ),
        SettingsItem.Toggle(
            title = stringResource(R.string.smooth_scrolling),
            subtitle = stringResource(R.string.smooth_scrolling_desc),
            checked = uiState.smoothScrolling,
            onToggle = viewModel::setSmoothScrolling
        )
    )
    "profiles" -> listOf(
        SettingsItem.Action(
            title = stringResource(R.string.switch_profile),
            description = "Pick, add or edit a profile",
            actionLabel = stringResource(R.string.settings_badge_open),
            onActivate = onSwitchProfile
        ),
        SettingsItem.Toggle(
            title = stringResource(R.string.skip_profile),
            subtitle = stringResource(R.string.skip_profile_desc),
            checked = uiState.skipProfileSelection,
            onToggle = viewModel::setSkipProfileSelection
        )
    )
    else -> emptyList()
}

@Composable
private fun SettingsItemRows(
    items: List<SettingsItem>,
    focusedIndex: Int,
    onRowClick: (Int) -> Unit
) {
    Column {
        items.forEachIndexed { index, item ->
            if (index > 0) Spacer(modifier = Modifier.height(10.dp))
            val modifier = Modifier.settingsFocusSlot(index)
            val isFocused = focusedIndex == index
            when (item) {
                is SettingsItem.Value -> SettingsRow(
                    icon = item.icon,
                    title = item.title,
                    subtitle = item.subtitle,
                    value = item.value,
                    isFocused = isFocused,
                    onClick = { onRowClick(index); item.onActivate() },
                    modifier = modifier
                )
                is SettingsItem.Toggle -> SettingsToggleRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    isEnabled = item.checked,
                    isFocused = isFocused,
                    onToggle = { onRowClick(index); item.onToggle(it) },
                    modifier = modifier
                )
                is SettingsItem.Action -> SettingsActionRow(
                    title = item.title,
                    description = item.description,
                    actionLabel = item.actionLabel,
                    isFocused = isFocused,
                    onClick = { onRowClick(index); item.onActivate() },
                    modifier = modifier
                )
            }
        }
    }
}

private fun sectionIcon(section: String): ImageVector = when (section) {
    "accounts" -> Icons.Default.Person
    "downloads" -> Icons.Default.Download
    "subtitles" -> Icons.Default.Subtitles
    "language" -> Icons.Default.Language
    "appearance" -> Icons.Default.Palette
    "profiles" -> Icons.Default.SwitchAccount
    else -> Icons.Default.Info
}

@Composable
private fun sectionTitle(section: String): String = when (section) {
    "accounts" -> stringResource(R.string.accounts)
    "downloads" -> "Downloads"
    "subtitles" -> stringResource(R.string.subtitles)
    "language" -> stringResource(R.string.language_and_audio)
    "appearance" -> stringResource(R.string.interface_label)
    "profiles" -> stringResource(R.string.profiles)
    else -> section.replaceFirstChar { it.uppercase() }
}

@Composable
private fun sectionDescription(section: String): String = when (section) {
    "accounts" -> "This TV's device id, app updates and credits"
    "downloads" -> "Live progress of everything downloading to the drive"
    "subtitles" -> "Subtitle size, colour and default language"
    "language" -> stringResource(R.string.settings_desc_language)
    "appearance" -> "Card layout, colours, clock and details screen"
    "profiles" -> stringResource(R.string.settings_desc_profiles)
    else -> ""
}

@Composable
private fun SettingsSectionHeader(section: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = sectionTitle(section),
            style = ArflixTypography.sectionTitle.copy(fontSize = 24.sp),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = sectionDescription(section),
            style = ArflixTypography.caption.copy(fontSize = 13.sp),
            color = TextSecondary.copy(alpha = 0.74f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsSectionItem(
    icon: ImageVector,
    title: String,
    isSelected: Boolean,
    isFocused: Boolean,
    onClick: () -> Unit
) {
    val bgColor = when {
        isFocused -> Color.White.copy(alpha = 0.12f)
        isSelected -> Color.White.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    val textColor = if (isFocused || isSelected) TextPrimary else TextSecondary
    val accentColor = resolveAccentColor(fallback = Pink)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(
                width = if (isFocused) 1.dp else 0.dp,
                color = if (isFocused) accentColor else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    if (isSelected || isFocused) accentColor.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.05f),
                    RoundedCornerShape(9.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused || isSelected) accentColor else textColor,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            style = ArflixTypography.body.copy(fontSize = 16.sp),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(accentColor, RoundedCornerShape(99.dp))
            )
        }
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    description: String,
    actionLabel: String,
    isFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRingColor = resolveAccentColor(fallback = Pink)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(
                if (isFocused) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(12.dp)
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) focusRingColor else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = ArflixTypography.cardTitle.copy(fontSize = 16.sp),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = ArflixTypography.caption.copy(fontSize = 13.sp),
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .background(Pink.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
                .border(1.dp, Pink.copy(alpha = 0.3f), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = actionLabel.uppercase(),
                style = ArflixTypography.label.copy(fontSize = 11.sp, letterSpacing = 0.5.sp),
                color = Pink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Downloads ───────────────────────────────────────────────────────────

/**
 * Settings → Downloads: what's downloading now (with MB/s + ETA), finished downloads
 * waiting to move onto the drive, what's queued, paused or failed, and how many titles
 * are on the drive. Focus slots follow [DownloadsUi.rows].
 */
@Composable
private fun DownloadsSettings(
    downloads: DownloadsUi,
    focusedIndex: Int,
    onRowClick: (Int) -> Unit
) {
    val context = LocalContext.current
    val isEmpty = downloads.rows.isEmpty()
    val amber = Color(0xFFF5A623)
    val red = Color(0xFFE5484D)
    var slot = 0

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(BackgroundElevated)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            DownloadStat("Downloading", downloads.downloading.size.toString(), Pink)
            DownloadStat("Queued", downloads.queued.size.toString(), TextSecondary)
            DownloadStat("Paused", downloads.paused.size.toString(), if (downloads.paused.isEmpty()) TextSecondary else amber)
            DownloadStat("Failed", downloads.failed.size.toString(), if (downloads.failed.isEmpty()) TextSecondary else red)
            DownloadStat("On drive", downloads.readyCount.toString(), SuccessGreen)
            DownloadStat(
                "Verified",
                "${downloads.healthyCount}/${downloads.readyCount}",
                if (downloads.healthyCount == downloads.readyCount) SuccessGreen else TextSecondary
            )
        }

        if (isEmpty) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Nothing downloading right now.",
                style = ArflixTypography.cardTitle.copy(fontSize = 16.sp),
                color = TextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "New titles added on the server start downloading here within a minute or so. " +
                    "${downloads.readyCount} already on the drive.",
                style = ArflixTypography.caption.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = TextSecondary
            )
            return@Column
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Press OK on any item to start, pause or stop it.",
            style = ArflixTypography.caption.copy(fontSize = 12.sp),
            color = TextSecondary.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 4.dp)
        )

        if (downloads.downloading.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            DownloadGroupLabel("DOWNLOADING NOW")
            downloads.downloading.forEach { item ->
                val index = slot++
                DownloadingCard(
                    item = item,
                    active = downloads.active?.takeIf { it.id == item.id },
                    isFocused = focusedIndex == index,
                    context = context,
                    modifier = Modifier.settingsFocusSlot(index).clickable { onRowClick(index) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (downloads.moving.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            DownloadGroupLabel("MOVING TO DRIVE")
            downloads.moving.forEach { item ->
                val index = slot++
                SimpleDownloadRow(
                    title = item.title,
                    trailing = if (downloads.isPlaying) "Waiting to move to drive" else "Moving to drive…",
                    dotColor = if (downloads.isPlaying) amber else SuccessGreen,
                    isFocused = focusedIndex == index,
                    modifier = Modifier.settingsFocusSlot(index).clickable { onRowClick(index) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (downloads.queued.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            DownloadGroupLabel("QUEUED")
            downloads.queued.forEach { item ->
                val index = slot++
                SimpleDownloadRow(
                    title = item.title,
                    trailing = item.sizeBytes?.let { formatBytes(context, it) } ?: "",
                    dotColor = TextSecondary.copy(alpha = 0.6f),
                    isFocused = focusedIndex == index,
                    modifier = Modifier.settingsFocusSlot(index).clickable { onRowClick(index) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (downloads.paused.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            DownloadGroupLabel("PAUSED")
            downloads.paused.forEach { item ->
                val index = slot++
                SimpleDownloadRow(
                    title = item.title,
                    trailing = if (item.progress > 0f) "${(item.progress * 100).toInt()}%" else "Paused",
                    dotColor = amber,
                    isFocused = focusedIndex == index,
                    modifier = Modifier.settingsFocusSlot(index).clickable { onRowClick(index) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (downloads.failed.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            DownloadGroupLabel("FAILED — WILL RETRY")
            downloads.failed.forEach { item ->
                val index = slot++
                SimpleDownloadRow(
                    title = item.title,
                    trailing = "Retrying",
                    dotColor = red,
                    isFocused = focusedIndex == index,
                    modifier = Modifier.settingsFocusSlot(index).clickable { onRowClick(index) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DownloadStat(label: String, value: String, valueColor: Color) {
    Column {
        Text(
            text = value,
            style = ArflixTypography.cardTitle.copy(fontSize = 22.sp),
            color = valueColor
        )
        Text(
            text = label.uppercase(),
            style = ArflixTypography.caption.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
            color = TextSecondary
        )
    }
}

@Composable
private fun DownloadGroupLabel(label: String) {
    Text(
        text = label,
        style = ArflixTypography.caption.copy(fontSize = 11.sp, letterSpacing = 1.sp),
        color = TextSecondary.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
    )
}

@Composable
private fun DownloadingCard(
    item: DownloadItemUi,
    active: com.arflix.tv.megaflix.ActiveDownload?,
    isFocused: Boolean,
    context: android.content.Context,
    modifier: Modifier = Modifier
) {
    val accent = resolveAccentColor(fallback = Pink)
    // Prefer the live stat when this is the item torrenting right now; fall back
    // to the persisted record's coarse % otherwise.
    val progress = (active?.progress ?: item.progress).coerceIn(0f, 1f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f))
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) accent else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = item.title,
                style = ArflixTypography.cardTitle.copy(fontSize = 16.sp),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${(progress * 100).toInt()}%",
                style = ArflixTypography.cardTitle.copy(fontSize = 15.sp),
                color = accent
            )
        }

        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent)
            )
        }

        Spacer(Modifier.height(8.dp))
        val downloaded = active?.downloadedBytes?.takeIf { it > 0L }
        val sizeText = when {
            downloaded != null && item.sizeBytes != null ->
                "${formatBytes(context, downloaded)} / ${formatBytes(context, item.sizeBytes)}"
            item.sizeBytes != null -> formatBytes(context, item.sizeBytes)
            else -> ""
        }
        val detail = listOfNotNull(
            sizeText.takeIf { it.isNotBlank() },
            active?.let { formatSpeed(context, it.speedBps) },
            active?.etaSeconds?.let { "${formatEta(it)} left" },
            active?.peers?.takeIf { it > 0 }?.let { "$it peers" },
            // Diagnostics while it's not moving: is the TV reaching the BitTorrent network at all?
            active?.takeIf { it.peers == 0 }?.let { if (it.dhtNodes > 0) "finding peers (${it.dhtNodes} DHT nodes)" else "no network peers yet" },
            active?.takeIf { it.throttled }?.let { "slowed while watching" }
        ).joinToString("  ·  ")
        Text(
            text = detail.ifBlank { "Connecting…" },
            style = ArflixTypography.caption.copy(fontSize = 13.sp),
            color = TextSecondary
        )
    }
}

@Composable
private fun SimpleDownloadRow(
    title: String,
    trailing: String,
    dotColor: Color,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = resolveAccentColor(fallback = Pink)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f))
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) accent else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(dotColor)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = ArflixTypography.cardTitle.copy(fontSize = 16.sp),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (trailing.isNotBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = trailing,
                style = ArflixTypography.caption.copy(fontSize = 13.sp),
                color = TextSecondary
            )
        }
    }
}

private fun formatBytes(context: android.content.Context, bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))

private fun formatSpeed(context: android.content.Context, bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0L) "0 B/s"
    else android.text.format.Formatter.formatShortFileSize(context, bytesPerSecond) + "/s"

private fun formatEta(seconds: Long): String {
    if (seconds <= 0L) return "0s"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

private class DownloadAction(val label: String, val color: Color, val onClick: () -> Unit)

/**
 * Start / Pause / Stop sheet for one download; the actions depend on its status.
 * D-pad up/down moves between buttons, OK activates, Back dismisses.
 */
@Composable
private fun DownloadActionsDialog(
    item: DownloadItemUi,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    val amber = Color(0xFFF5A623)
    val red = Color(0xFFE5484D)
    val actions = remember(item.id, item.status) {
        buildList {
            when (item.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.COMING_SOON -> {
                    add(DownloadAction("Pause", amber, onPause))
                    add(DownloadAction("Stop", red, onStop))
                }
                DownloadStatus.PAUSED -> {
                    add(DownloadAction("Start", SuccessGreen, onStart))
                    add(DownloadAction("Stop", red, onStop))
                }
                DownloadStatus.FAILED -> {
                    add(DownloadAction("Retry", SuccessGreen, onStart))
                    add(DownloadAction("Stop", red, onStop))
                }
                DownloadStatus.READY -> Unit
            }
            add(DownloadAction("Cancel", TextSecondary, onDismiss))
        }
    }

    var focusedIndex by remember(item.id) { mutableIntStateOf(0) }
    val dialogFocus = remember { FocusRequester() }
    LaunchedEffect(item.id) { dialogFocus.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        BackHandler { onDismiss() }
        ModalScrim(onDismiss = onDismiss) {
            Column(
                modifier = Modifier
                    .width(440.dp)
                    .background(BackgroundElevated, RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                    .padding(24.dp)
                    .focusRequester(dialogFocus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> {
                                focusedIndex = (focusedIndex - 1 + actions.size) % actions.size
                                true
                            }
                            Key.DirectionDown -> {
                                focusedIndex = (focusedIndex + 1) % actions.size
                                true
                            }
                            Key.Enter, Key.DirectionCenter -> {
                                actions.getOrNull(focusedIndex)?.onClick?.invoke()
                                true
                            }
                            Key.Back -> { onDismiss(); true }
                            else -> false
                        }
                    }
            ) {
                Text(
                    text = "Manage download",
                    style = ArflixTypography.cardTitle.copy(fontSize = 18.sp),
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.title,
                    style = ArflixTypography.caption.copy(fontSize = 13.sp),
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(18.dp))
                actions.forEachIndexed { index, action ->
                    val isFocused = focusedIndex == index
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isFocused) action.color.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f))
                            .border(
                                width = if (isFocused) 2.dp else 0.dp,
                                color = if (isFocused) action.color else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { action.onClick() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = action.label,
                            style = ArflixTypography.cardTitle.copy(fontSize = 16.sp),
                            color = if (isFocused) action.color else TextPrimary
                        )
                    }
                    if (index < actions.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ── Dialogs ─────────────────────────────────────────────────────────────

@Composable
private fun ModalScrim(
    onDismiss: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val contentInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.clickable(interactionSource = contentInteraction, indication = null, onClick = {}),
            content = content
        )
    }
}

/** A single-choice list. D-pad up/down moves, OK selects, Back dismisses. */
@Composable
private fun OptionPickerModal(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var focusedIndex by remember(title) { mutableIntStateOf(selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))) }

    LaunchedEffect(focusedIndex) {
        if (options.isNotEmpty()) listState.animateScrollToItem(focusedIndex)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        BackHandler { onDismiss() }
        ModalScrim(onDismiss = onDismiss) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .background(BackgroundElevated, RoundedCornerShape(16.dp))
                    .padding(28.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Back, Key.Escape -> { onDismiss(); true }
                            Key.DirectionUp -> {
                                if (focusedIndex > 0) focusedIndex--
                                true
                            }
                            Key.DirectionDown -> {
                                if (focusedIndex < options.lastIndex) focusedIndex++
                                true
                            }
                            Key.Enter, Key.DirectionCenter -> {
                                if (options.isNotEmpty()) onSelect(focusedIndex)
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                Text(text = title, style = ArflixTypography.sectionTitle, color = TextPrimary)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 360.dp).arvioDpadFocusGroup()
                ) {
                    itemsIndexed(options) { index, option ->
                        val isFocused = index == focusedIndex
                        val focusColor = resolveAccentColor(fallback = Pink)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isFocused) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    width = if (isFocused) 2.dp else 1.dp,
                                    color = if (isFocused) focusColor else Color.White.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onSelect(index) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = option,
                                style = ArflixTypography.body,
                                color = if (isFocused) TextPrimary else TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            if (index == selectedIndex) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = SuccessGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_press_enter_to_select),
                    style = ArflixTypography.caption,
                    color = TextSecondary.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Device ID entry. D-pad navigates field → paste → cancel/confirm; OK on the field
 * opens the keyboard; Back closes the keyboard first, then the dialog.
 */
@Composable
private fun DeviceIdInputModal(
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // 0 = field, 1 = paste, 2 = cancel, 3 = confirm
    var focusedIndex by remember { mutableIntStateOf(0) }
    var value by remember { mutableStateOf(initialValue) }
    var editText by remember { mutableStateOf<EditText?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val modalFocusRequester = remember { FocusRequester() }
    val accent = resolveAccentColor(fallback = Pink)

    fun imm() = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    fun hideKeyboard() {
        val edit = editText ?: return
        imm()?.hideSoftInputFromWindow(edit.windowToken, 0)
        edit.clearFocus()
        runCatching { modalFocusRequester.requestFocus() }
    }

    fun showKeyboard() {
        val edit = editText ?: return
        edit.post {
            edit.requestFocus()
            val shown = imm()?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) ?: false
            if (!shown) imm()?.showSoftInput(edit, InputMethodManager.SHOW_FORCED)
        }
    }

    fun paste() {
        val text = clipboardManager.getText()?.text ?: return
        value = text
        editText?.let {
            it.setText(text)
            it.setSelection(text.length)
        }
        hideKeyboard()
    }

    val dismiss = {
        hideKeyboard()
        onDismiss()
    }
    val confirm = {
        hideKeyboard()
        onConfirm(value)
    }

    LaunchedEffect(Unit) { modalFocusRequester.requestFocus() }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        BackHandler { dismiss() }
        ModalScrim(onDismiss = dismiss) {
            Column(
                modifier = Modifier
                    .width(620.dp)
                    .background(BackgroundElevated, RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .focusRequester(modalFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Back, Key.Escape -> {
                                if (editText?.hasFocus() == true) hideKeyboard() else dismiss()
                                true
                            }
                            Key.DirectionUp -> {
                                focusedIndex = when (focusedIndex) {
                                    2, 3 -> 1
                                    else -> 0
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                focusedIndex = when (focusedIndex) {
                                    0 -> 1
                                    1 -> 3
                                    else -> focusedIndex
                                }
                                true
                            }
                            Key.DirectionLeft -> {
                                if (focusedIndex == 3) focusedIndex = 2
                                true
                            }
                            Key.DirectionRight -> {
                                if (focusedIndex == 2) focusedIndex = 3
                                true
                            }
                            Key.Enter, Key.DirectionCenter -> {
                                when (focusedIndex) {
                                    0 -> showKeyboard()
                                    1 -> paste()
                                    2 -> dismiss()
                                    else -> confirm()
                                }
                                true
                            }
                            else -> false
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Device ID", style = ArflixTypography.sectionTitle, color = TextPrimary)
                Text(
                    text = stringResource(R.string.settings_modal_hint_tv),
                    style = ArflixTypography.caption,
                    color = TextSecondary.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
                Text(
                    text = "Identifies this TV to the feed.",
                    style = ArflixTypography.caption,
                    color = TextSecondary.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                val fieldFocused = focusedIndex == 0
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = if (fieldFocused) 0.12f else 0.05f), RoundedCornerShape(10.dp))
                        .border(
                            width = if (fieldFocused) 2.dp else 1.dp,
                            color = if (fieldFocused) accent else Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(2.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            EditText(ctx).apply {
                                editText = this
                                setText(value)
                                setTextColor(android.graphics.Color.WHITE)
                                setHintTextColor(android.graphics.Color.GRAY)
                                hint = "e.g. T_12345_XX"
                                textSize = 16f
                                background = null
                                setPadding(20, 14, 20, 14)
                                isSingleLine = true
                                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                                imeOptions = EditorInfo.IME_ACTION_DONE
                                doAfterTextChanged { value = it?.toString().orEmpty() }
                                setOnFocusChangeListener { _, hasFocus -> if (hasFocus) focusedIndex = 0 }
                                // Hand D-pad navigation back to the dialog instead of the EditText.
                                setOnKeyListener { _, keyCode, keyEvent ->
                                    if (keyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                                    when (keyCode) {
                                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                            hideKeyboard()
                                            focusedIndex = 1
                                            true
                                        }
                                        android.view.KeyEvent.KEYCODE_BACK -> {
                                            hideKeyboard()
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                setOnEditorActionListener { _, actionId, _ ->
                                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                                        hideKeyboard()
                                        focusedIndex = 3
                                        true
                                    } else false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                val pasteFocused = focusedIndex == 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (pasteFocused) Color.White else Color.Black.copy(alpha = 0.82f), RoundedCornerShape(10.dp))
                        .border(1.dp, if (pasteFocused) Color.White else Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                        .clickable { paste() }
                        .padding(vertical = 11.dp, horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = stringResource(R.string.settings_cd_paste),
                        tint = if (pasteFocused) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_paste_into, "Device ID"),
                        style = ArflixTypography.button,
                        color = if (pasteFocused) Color.Black else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DialogButton(
                        label = stringResource(R.string.cancel),
                        isFocused = focusedIndex == 2,
                        onClick = dismiss,
                        modifier = Modifier.weight(1f)
                    )
                    DialogButton(
                        label = stringResource(R.string.confirm),
                        isFocused = focusedIndex == 3,
                        onClick = confirm,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    isFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) Color.White else Color.Black.copy(alpha = 0.82f), RoundedCornerShape(10.dp))
            .border(1.dp, if (isFocused) Color.White else Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = ArflixTypography.button,
            color = if (isFocused) Color.Black else Color.White
        )
    }
}

@Composable
private fun UnknownSourcesModal(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var focusedIndex by remember { mutableIntStateOf(1) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        BackHandler { onDismiss() }
        ModalScrim(onDismiss = onDismiss) {
            Column(
                modifier = Modifier
                    .width(620.dp)
                    .background(BackgroundElevated, RoundedCornerShape(18.dp))
                    .padding(28.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Back, Key.Escape -> { onDismiss(); true }
                            Key.DirectionLeft -> { focusedIndex = 0; true }
                            Key.DirectionRight -> { focusedIndex = 1; true }
                            Key.Enter, Key.DirectionCenter -> {
                                if (focusedIndex == 0) onDismiss() else onOpenSettings()
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                Text(stringResource(R.string.settings_allow_unknown_sources), style = ArflixTypography.sectionTitle, color = TextPrimary)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.settings_allow_unknown_sources_desc),
                    style = ArflixTypography.body,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UpdateActionButton(stringResource(R.string.close), focusedIndex == 0, onDismiss)
                    UpdateActionButton(stringResource(R.string.settings_open_settings), focusedIndex == 1, onOpenSettings, highlighted = true)
                }
            }
        }
    }
}

@Composable
private fun UpdateActionButton(
    label: String,
    isFocused: Boolean,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    val background = when {
        highlighted && isFocused -> Pink
        isFocused -> Color.White.copy(alpha = 0.16f)
        highlighted -> Pink.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val textColor = when {
        highlighted && isFocused -> Color.Black
        highlighted -> Color.White
        isFocused -> TextPrimary
        else -> TextSecondary
    }
    val borderColor = when {
        highlighted && isFocused -> Pink.copy(alpha = 0.95f)
        highlighted -> Pink.copy(alpha = 0.4f)
        isFocused -> Color.White.copy(alpha = 0.75f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background, RoundedCornerShape(10.dp))
            .border(
                width = if (isFocused || highlighted) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, style = ArflixTypography.button, color = textColor)
    }
}
