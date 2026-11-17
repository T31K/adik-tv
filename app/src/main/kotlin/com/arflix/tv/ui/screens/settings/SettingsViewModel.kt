package com.arflix.tv.ui.screens.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.R
import com.arflix.tv.data.model.DownloadStatus
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.megaflix.ActiveDownload
import com.arflix.tv.megaflix.DownloadService
import com.arflix.tv.megaflix.DownloadStateStore
import com.arflix.tv.megaflix.MegaflixDownloadManager
import com.arflix.tv.megaflix.MegaflixSyncManager
import com.arflix.tv.player.PlaybackState
import com.arflix.tv.ui.components.CARD_LAYOUT_MODE_LANDSCAPE
import com.arflix.tv.ui.components.CARD_LAYOUT_MODE_POSTER
import com.arflix.tv.ui.components.ToastType
import com.arflix.tv.ui.components.normalizeCardLayoutMode
import com.arflix.tv.updater.ApkDownloader
import com.arflix.tv.updater.ApkInstaller
import com.arflix.tv.updater.AppUpdateRepository
import com.arflix.tv.updater.UpdatePreferences
import com.arflix.tv.updater.UpdateStatus
import com.arflix.tv.updater.UpdateStatusManager
import com.arflix.tv.updater.VersionUtils
import com.arflix.tv.util.ACCENT_COLOR_KEY
import com.arflix.tv.util.LAST_APP_LANGUAGE_KEY
import com.arflix.tv.util.OLED_BLACK_BACKGROUND_KEY
import com.arflix.tv.util.PlayerPrefs
import com.arflix.tv.util.PlayerPrefsValues
import com.arflix.tv.util.SKIP_PROFILE_SELECTION_KEY
import com.arflix.tv.util.resolveAppLanguage
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * A user-facing settings message, kept as a resource reference until a composable renders it,
 * so it follows the language selected in the app rather than the system language.
 */
sealed interface SettingsMessage {
    data class Res(
        @param:StringRes val resourceId: Int,
        val formatArgs: List<Any> = emptyList()
    ) : SettingsMessage

    /** Text without a resource (e.g. a platform exception message); shown as it is. */
    data class Raw(val text: String) : SettingsMessage
}

/** One row in the Settings → Downloads section. */
data class DownloadItemUi(
    val id: String,
    val title: String,
    val status: DownloadStatus,
    val progress: Float,
    val sizeBytes: Long?
)

/** Everything the Settings → Downloads section renders. */
data class DownloadsUi(
    val active: ActiveDownload? = null, // live MB/s + ETA for the item torrenting now
    val downloading: List<DownloadItemUi> = emptyList(),
    /** Finished downloads in internal storage waiting to be moved onto the drive. */
    val moving: List<DownloadItemUi> = emptyList(),
    val queued: List<DownloadItemUi> = emptyList(),
    val paused: List<DownloadItemUi> = emptyList(),
    val failed: List<DownloadItemUi> = emptyList(),
    val readyCount: Int = 0,
    val healthyCount: Int = 0, // ready files that passed the full torrent hash check
    /** A movie is playing, so drive moves wait until it ends. */
    val isPlaying: Boolean = false
) {
    /** Rows in render (and focus) order. */
    val rows: List<DownloadItemUi> get() = downloading + moving + queued + paused + failed
}

data class SettingsUiState(
    /** Which family TV this is; sent to the feed. Null = not set. */
    val megaflixDeviceId: String? = null,
    val downloads: DownloadsUi = DownloadsUi(),
    /** libVLC subtitle style + preferred audio/subtitle languages (device-wide). */
    val player: PlayerPrefsValues = PlayerPrefsValues(),
    /** App UI / metadata language tag (e.g. "en-US"). */
    val contentLanguage: String = "en-US",
    // Appearance
    val cardLayoutMode: String = CARD_LAYOUT_MODE_LANDSCAPE,
    val accentColor: String = "White",
    val oledBlackBackground: Boolean = false,
    val clockFormat: String = "24h",
    val showBudget: Boolean = true,
    val showEpisodeRatings: Boolean = false,
    val spoilerBlurEnabled: Boolean = false,
    val smoothScrolling: Boolean = true,
    // Profiles
    val skipProfileSelection: Boolean = false,
    // App updates
    val isSelfUpdateSupported: Boolean = true,
    val updateStatus: UpdateStatus = UpdateStatus.Idle,
    val showAppUpdateDialog: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    // Toast
    val toastMessage: SettingsMessage? = null,
    val toastType: ToastType = ToastType.INFO
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
    private val mediaRepository: MediaRepository,
    private val appUpdateRepository: AppUpdateRepository,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader,
    private val updateStatusManager: UpdateStatusManager,
    private val megaflixStore: DownloadStateStore,
    private val megaflixDownloadManager: MegaflixDownloadManager,
    private val megaflixSyncManager: MegaflixSyncManager,
    private val playbackState: PlaybackState
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var loadSettingsJob: Job? = null
    private var observedProfileId: String? = null
    private var downloadJob: Job? = null

    private fun contentLanguageKey() = profileManager.profileStringKey("content_language")
    private fun cardLayoutModeKey() = profileManager.profileStringKey("card_layout_mode")
    private fun clockFormatKey() = profileManager.profileStringKey("clock_format")
    private fun showBudgetKey() = profileManager.profileBooleanKey("show_budget_on_home")
    private fun showEpisodeRatingsKey() = profileManager.profileBooleanKey("show_episode_ratings")
    private fun spoilerBlurKey() = profileManager.profileBooleanKey("spoiler_blur")
    private fun smoothScrollingKey() = profileManager.profileBooleanKey("smooth_scrolling")

    init {
        viewModelScope.launch {
            runCatching { megaflixStore.getDeviceId() }.getOrNull()?.let { id ->
                _uiState.value = _uiState.value.copy(megaflixDeviceId = id)
            }
        }
        observeProfileChanges()
        observePlayerPrefs()
        observeDownloads()
        initializeUpdaterState()
        checkForAppUpdates(force = false, showNoUpdateFeedback = false)
    }

    // ── Accounts: device id ──────────────────────────────────────────────

    /** The owner types this TV's fleet id once (valid list lives server-side). */
    fun setMegaflixDeviceId(raw: String) {
        viewModelScope.launch {
            val id = raw.trim().takeIf { it.isNotBlank() }
            runCatching { megaflixStore.setDeviceId(id) }
            _uiState.value = _uiState.value.copy(megaflixDeviceId = id)
        }
    }

    // ── Downloads ────────────────────────────────────────────────────────

    /** Start also kicks the drain service. */
    fun startDownload(id: String) {
        viewModelScope.launch {
            megaflixDownloadManager.start(id)
            runCatching { DownloadService.start(context) }
        }
    }

    fun pauseDownload(id: String) {
        viewModelScope.launch { megaflixDownloadManager.pause(id) }
    }

    fun stopDownload(id: String) {
        viewModelScope.launch { megaflixDownloadManager.stop(id) }
    }

    /**
     * Joins the persisted per-item records (status + %) with the feed (titles), the live
     * in-memory active-download stats (MB/s + ETA, never persisted) and the playback flag
     * (drive moves wait while a movie plays).
     */
    private fun observeDownloads() {
        viewModelScope.launch {
            combine(
                megaflixStore.recordsFlow(),
                megaflixSyncManager.feedItems,
                megaflixDownloadManager.activeDownload,
                megaflixStore.healthyFilesFlow(),
                playbackState.isPlaying
            ) { records, feed, active, healthy, isPlaying ->
                // Fingerprints are "path|size|mtime"; match on path (no disk I/O here).
                val healthyPaths = healthy.map { it.substringBefore('|') }.toSet()
                val titles = feed.associate { item ->
                    val base = item.title.ifBlank { item.id }
                    val label = if (item.season != null && item.episode != null) {
                        "$base S${item.season}E${item.episode}"
                    } else base
                    item.id to label
                }
                val (staged, pending) = records.values.partition { it.stagedPath != null }
                fun toUi(list: List<com.arflix.tv.megaflix.DownloadRecord>) = list
                    .map { r ->
                        DownloadItemUi(
                            id = r.id,
                            title = titles[r.id] ?: r.id,
                            status = r.status,
                            progress = r.progress,
                            sizeBytes = r.sizeBytes
                        )
                    }
                    .sortedBy { it.title.lowercase() }
                fun rows(status: DownloadStatus) = toUi(pending.filter { it.status == status })
                DownloadsUi(
                    active = active,
                    downloading = rows(DownloadStatus.DOWNLOADING),
                    moving = toUi(staged),
                    queued = rows(DownloadStatus.COMING_SOON),
                    paused = rows(DownloadStatus.PAUSED),
                    failed = rows(DownloadStatus.FAILED),
                    readyCount = records.values.count { it.status == DownloadStatus.READY },
                    healthyCount = records.values.count {
                        it.status == DownloadStatus.READY && it.localFilePath in healthyPaths
                    },
                    isPlaying = isPlaying
                )
            }.collect { downloads ->
                _uiState.value = _uiState.value.copy(downloads = downloads)
            }
        }
    }

    // ── Subtitles / audio (libVLC) ───────────────────────────────────────

    private fun observePlayerPrefs() {
        viewModelScope.launch {
            PlayerPrefs.observe(context).collect { prefs ->
                _uiState.value = _uiState.value.copy(player = prefs)
            }
        }
    }

    fun cycleSubtitleSize() {
        val current = _uiState.value.player.subtitleScalePercent
        val next = SUBTITLE_SIZES.firstOrNull { it > current } ?: SUBTITLE_SIZES.first()
        viewModelScope.launch { PlayerPrefs.setSubtitleScale(context, next) }
    }

    fun cycleSubtitleColor() {
        val current = _uiState.value.player.subtitleColor
        val index = SUBTITLE_COLORS.indexOfFirst { it.second == current }
        val next = SUBTITLE_COLORS[(index + 1).mod(SUBTITLE_COLORS.size)]
        viewModelScope.launch { PlayerPrefs.setSubtitleColor(context, next.second) }
    }

    fun setSubtitleBackground(enabled: Boolean) {
        viewModelScope.launch { PlayerPrefs.setSubtitleBackground(context, enabled) }
    }

    /** Blank = subtitles off by default. */
    fun setSubtitleLanguage(iso639_2: String) {
        viewModelScope.launch { PlayerPrefs.setSubtitleLanguage(context, iso639_2) }
    }

    fun setAudioLanguage(iso639_2: String) {
        viewModelScope.launch { PlayerPrefs.setAudioLanguage(context, iso639_2.ifBlank { "eng" }) }
    }

    // ── Profile-scoped settings (language, appearance, profiles) ────────

    private fun observeProfileChanges() {
        viewModelScope.launch {
            profileManager.activeProfileId.collect { profileId ->
                if (observedProfileId == profileId) return@collect
                observedProfileId = profileId
                loadSettings()
            }
        }
    }

    private fun loadSettings() {
        loadSettingsJob?.cancel()
        loadSettingsJob = viewModelScope.launch {
            val profileId = profileManager.getProfileIdSync()
            val prefs = context.settingsDataStore.data.first()
            val contentLang = resolveAppLanguage(prefs, profileId)
            mediaRepository.contentLanguage = contentLang
            if (profileManager.getProfileIdSync() != profileId) return@launch
            _uiState.value = _uiState.value.copy(
                contentLanguage = contentLang,
                cardLayoutMode = normalizeCardLayoutMode(prefs[cardLayoutModeKey()]),
                accentColor = prefs[ACCENT_COLOR_KEY] ?: "White",
                oledBlackBackground = prefs[OLED_BLACK_BACKGROUND_KEY] ?: false,
                clockFormat = prefs[clockFormatKey()] ?: "24h",
                showBudget = prefs[showBudgetKey()] ?: true,
                showEpisodeRatings = prefs[showEpisodeRatingsKey()] ?: false,
                spoilerBlurEnabled = prefs[spoilerBlurKey()] ?: false,
                smoothScrolling = prefs[smoothScrollingKey()] ?: true,
                skipProfileSelection = prefs[SKIP_PROFILE_SELECTION_KEY] ?: false
            )
        }
    }

    /** Set the app UI / TMDB metadata language (e.g. "en-US", "fr-FR"). */
    fun setContentLanguage(lang: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[contentLanguageKey()] = lang
                prefs[LAST_APP_LANGUAGE_KEY] = lang
            }
            // Mirror to SharedPreferences so attachBaseContext can read it synchronously on next launch.
            context.getSharedPreferences("app_locale", Context.MODE_PRIVATE)
                .edit().putString("locale_tag", lang).apply()
            mediaRepository.contentLanguage = lang
            _uiState.value = _uiState.value.copy(contentLanguage = lang)
        }
    }

    fun toggleCardLayoutMode() {
        val next = if (_uiState.value.cardLayoutMode.equals(CARD_LAYOUT_MODE_POSTER, ignoreCase = true)) {
            CARD_LAYOUT_MODE_LANDSCAPE
        } else {
            CARD_LAYOUT_MODE_POSTER
        }
        val normalized = normalizeCardLayoutMode(next)
        viewModelScope.launch {
            context.settingsDataStore.edit { it[cardLayoutModeKey()] = normalized }
            _uiState.value = _uiState.value.copy(cardLayoutMode = normalized)
        }
    }

    /** White → Red → Orange → Yellow → Green → Blue → Indigo → Violet → White. */
    fun cycleAccentColor() {
        val current = _uiState.value.accentColor
        val next = ACCENT_COLORS[(ACCENT_COLORS.indexOf(current) + 1) % ACCENT_COLORS.size]
        viewModelScope.launch {
            context.settingsDataStore.edit { it[ACCENT_COLOR_KEY] = next }
            _uiState.value = _uiState.value.copy(accentColor = next)
        }
    }

    fun setOledBlackBackground(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[OLED_BLACK_BACKGROUND_KEY] = enabled }
            _uiState.value = _uiState.value.copy(oledBlackBackground = enabled)
        }
    }

    fun cycleClockFormat() {
        val next = if (_uiState.value.clockFormat == "24h") "12h" else "24h"
        viewModelScope.launch {
            context.settingsDataStore.edit { it[clockFormatKey()] = next }
            _uiState.value = _uiState.value.copy(clockFormat = next)
        }
    }

    fun setShowBudget(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[showBudgetKey()] = enabled }
            _uiState.value = _uiState.value.copy(showBudget = enabled)
        }
    }

    fun setShowEpisodeRatings(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[showEpisodeRatingsKey()] = enabled }
            _uiState.value = _uiState.value.copy(showEpisodeRatings = enabled)
        }
    }

    fun setSpoilerBlurEnabled(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[spoilerBlurKey()] = enabled }
            _uiState.value = _uiState.value.copy(spoilerBlurEnabled = enabled)
        }
    }

    fun setSmoothScrolling(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[smoothScrollingKey()] = enabled }
            _uiState.value = _uiState.value.copy(smoothScrolling = enabled)
        }
    }

    fun setSkipProfileSelection(skip: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SKIP_PROFILE_SELECTION_KEY] = skip }
            _uiState.value = _uiState.value.copy(skipProfileSelection = skip)
        }
    }

    // ── App updates ──────────────────────────────────────────────────────

    private fun initializeUpdaterState() {
        _uiState.value = _uiState.value.copy(
            isSelfUpdateSupported = appUpdateRepository.supportsSelfUpdate()
        )
        // If the app was updated to a new version, clear any previously ignored tag
        // so future updates are shown again.
        viewModelScope.launch {
            val ignoredTag = updatePreferences.ignoredTag.first()
            if (ignoredTag != null) {
                val installedVersion = appUpdateRepository.getInstalledVersionName()
                val ignoredNormalized = VersionUtils.normalize(ignoredTag)
                val installedNormalized = VersionUtils.normalize(installedVersion)
                if (ignoredNormalized == installedNormalized || !VersionUtils.isRemoteNewer(ignoredTag, installedVersion)) {
                    updatePreferences.setIgnoredTag(null)
                }
            }
        }
        viewModelScope.launch {
            updateStatusManager.status.collect { status ->
                _uiState.value = _uiState.value.copy(updateStatus = status)
            }
        }
    }

    fun checkForAppUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!appUpdateRepository.supportsSelfUpdate()) {
            _uiState.value = _uiState.value.copy(showAppUpdateDialog = force)
            return
        }

        viewModelScope.launch {
            updateStatusManager.updateStatus(UpdateStatus.Checking)
            val result = appUpdateRepository.getLatestUpdate()
            updatePreferences.setLastCheckAtMs(System.currentTimeMillis())

            result.onSuccess { update ->
                val localVer = appUpdateRepository.getInstalledVersionName()
                if (VersionUtils.isRemoteNewer(update.tag, localVer)) {
                    updateStatusManager.updateStatus(UpdateStatus.UpdateAvailable(update))
                    // A forced check shows the dialog even if this release was ignored.
                    if (force) {
                        _uiState.value = _uiState.value.copy(showAppUpdateDialog = true)
                    }
                } else {
                    if (showNoUpdateFeedback) {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = SettingsMessage.Res(R.string.settings_update_already_latest),
                            toastType = ToastType.INFO
                        )
                    }
                    updateStatusManager.reset()
                }
            }.onFailure { error ->
                if (showNoUpdateFeedback) {
                    _uiState.value = _uiState.value.copy(
                        toastMessage = error.message?.takeIf { it.isNotBlank() }?.let { SettingsMessage.Raw(it) }
                            ?: SettingsMessage.Res(R.string.update_check_failed),
                        toastType = ToastType.ERROR
                    )
                }
                updateStatusManager.reset()
            }
        }
    }

    fun dismissAppUpdateDialog() {
        _uiState.value = _uiState.value.copy(showAppUpdateDialog = false, showUnknownSourcesDialog = false)
    }

    fun ignoreAppUpdate() {
        val currentStatus = updateStatusManager.status.value
        if (currentStatus is UpdateStatus.UpdateAvailable) {
            updateStatusManager.sessionIgnoredTag = currentStatus.update.tag
            viewModelScope.launch {
                updatePreferences.setIgnoredTag(currentStatus.update.tag)
            }
        }
        _uiState.value = _uiState.value.copy(showAppUpdateDialog = false)
        updateStatusManager.reset()
    }

    fun downloadAppUpdate() {
        val update = when (val currentStatus = updateStatusManager.status.value) {
            is UpdateStatus.UpdateAvailable -> currentStatus.update
            is UpdateStatus.Failure -> currentStatus.update
            else -> return
        } ?: return

        if (!appUpdateRepository.supportsSelfUpdate()) return

        downloadJob = viewModelScope.launch {
            updateStatusManager.updateStatus(UpdateStatus.Downloading(0f, update))

            val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(File(context.cacheDir, "updates"), safeName)

            val result = withContext(Dispatchers.IO) {
                apkDownloader.download(update.assetUrl, dest) { downloaded, total ->
                    val progress = if (total != null && total > 0L) {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else null
                    updateStatusManager.updateStatus(UpdateStatus.Downloading(progress, update))
                }
            }

            result.onSuccess { file ->
                updateStatusManager.updateStatus(UpdateStatus.ReadyToInstall(file.absolutePath, update))
                installAppUpdateOrRequestPermission()
            }.onFailure { error ->
                updateStatusManager.updateStatus(
                    UpdateStatus.Failure(error.message ?: context.getString(R.string.update_download_failed), update)
                )
            }
        }
    }

    fun cancelDownloadAppUpdate() {
        downloadJob?.cancel()
        downloadJob = null
        val currentStatus = updateStatusManager.status.value
        if (currentStatus is UpdateStatus.Downloading) {
            updateStatusManager.updateStatus(UpdateStatus.UpdateAvailable(currentStatus.update))
        }
    }

    fun installAppUpdateOrRequestPermission() {
        val currentStatus = updateStatusManager.status.value as? UpdateStatus.ReadyToInstall ?: return
        val update = currentStatus.update
        val apkFile = File(currentStatus.apkPath)

        if (!apkFile.exists()) {
            updateStatusManager.updateStatus(UpdateStatus.Failure("Downloaded file is missing", update))
            return
        }

        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.value = _uiState.value.copy(showUnknownSourcesDialog = true, showAppUpdateDialog = false)
            return
        }

        val conflictMsg = ApkInstaller.checkSignatureConflict(context, apkFile)
        if (conflictMsg != null) {
            updateStatusManager.updateStatus(UpdateStatus.Failure(conflictMsg, update))
            return
        }

        ApkInstaller.launchInstall(context, apkFile)
        updateStatusManager.updateStatus(UpdateStatus.Installing(update))

        viewModelScope.launch {
            updatePreferences.setIgnoredTag(update.tag)
        }
    }

    fun openUnknownSourcesSettings() {
        ApkInstaller.buildUnknownSourcesSettingsIntent(context)?.let { intent ->
            context.startActivity(intent)
        }
    }

    fun dismissToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    companion object {
        /** Subtitle size steps, percent of VLC's default. */
        val SUBTITLE_SIZES = listOf(75, 100, 125, 150, 200)

        /** Subtitle colours: label to 0xRRGGBB. */
        val SUBTITLE_COLORS = listOf(
            "White" to 0xFFFFFF,
            "Yellow" to 0xFFFF00,
            "Cyan" to 0x00FFFF,
            "Green" to 0x00FF00
        )

        /** Languages offered for audio/subtitles: ISO 639-2 code to display name. */
        val PLAYER_LANGUAGES = listOf(
            "eng" to "English",
            "chi" to "Chinese",
            "msa" to "Malay",
            "tam" to "Tamil",
            "hin" to "Hindi",
            "ind" to "Indonesian",
            "jpn" to "Japanese",
            "kor" to "Korean",
            "spa" to "Spanish",
            "fre" to "French",
            "ger" to "German",
            "ara" to "Arabic"
        )

        val ACCENT_COLORS = listOf("White", "Red", "Orange", "Yellow", "Green", "Blue", "Indigo", "Violet")
    }
}
