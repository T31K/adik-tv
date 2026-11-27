package com.arflix.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.PlaybackProgressStore
import com.arflix.tv.megaflix.MegaflixLibraryBuilder
import com.arflix.tv.player.PlaybackState
import com.arflix.tv.stream.DecryptingProxy
import com.arflix.tv.stream.StreamEnd
import com.arflix.tv.stream.StreamSession
import com.arflix.tv.stream.StreamUris
import com.arflix.tv.util.PlayerPrefs
import com.arflix.tv.util.PlayerPrefsValues
import kotlinx.coroutines.runBlocking
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import javax.inject.Inject

data class VlcTrack(val id: Int, val name: String)

data class VlcPlayerUiState(
    val title: String = "",
    val subtitle: String? = null,
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val audioTracks: List<VlcTrack> = emptyList(),
    val subtitleTracks: List<VlcTrack> = emptyList(),
    val selectedAudioId: Int = -1,
    val selectedSubtitleId: Int = -1,
    val hasNextEpisode: Boolean = false,
    /** Series show an "Episodes" button in the controls. */
    val isSeries: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false
)

/**
 * libVLC-backed player for local drive files.
 *
 * Hardware decoding is on with automatic software fallback (libVLC tries MediaCodec
 * first and drops to avcodec when it can't open a stream). If playback still fails,
 * the file is retried once with hardware decoding off before an error is shown.
 */
@HiltViewModel
class VlcPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val progressStore: PlaybackProgressStore,
    private val libraryBuilder: MegaflixLibraryBuilder,
    private val playbackState: PlaybackState,
    private val streamSession: StreamSession
) : ViewModel() {

    // Stream movies (adikstream://): the decrypting proxy for this player session and its URL.
    private var streamProxy: DecryptingProxy? = null
    private var streamProxyUrl: String? = null

    private val showTitle: String = savedStateHandle.get<String>(ARG_TITLE).orEmpty()
    private val tmdbId: Int = savedStateHandle.get<Int>(ARG_TMDB_ID) ?: 0
    private val mediaType: MediaType =
        if (savedStateHandle.get<String>(ARG_TYPE) == "tv") MediaType.TV else MediaType.MOVIE
    private var season: Int? = savedStateHandle.get<Int>(ARG_SEASON)?.takeIf { it >= 0 }
    private var episode: Int? = savedStateHandle.get<Int>(ARG_EPISODE)?.takeIf { it >= 0 }
    private var uri: String = savedStateHandle.get<String>(ARG_URI).orEmpty()
    private var startPositionMs: Long = savedStateHandle.get<Long>(ARG_START_MS)?.takeIf { it > 0L } ?: -1L

    private val _uiState = MutableStateFlow(
        VlcPlayerUiState(title = showTitle, subtitle = episodeLabel(), isSeries = mediaType == MediaType.TV)
    )
    val uiState: StateFlow<VlcPlayerUiState> = _uiState.asStateFlow()

    // Tiny DataStore read (already cached after app start); options are fixed per LibVLC instance.
    private val prefs: PlayerPrefsValues = runBlocking(Dispatchers.IO) { PlayerPrefs.read(context) }
    private val autoSubtitles: Boolean = prefs.subtitleLanguage.isNotBlank()
    private val libVlc: LibVLC = LibVLC(context, vlcOptions(prefs))
    private val player: MediaPlayer = MediaPlayer(libVlc)
    private var attachedLayout: VLCVideoLayout? = null
    private var started = false
    private var softwareRetryDone = false
    private var subtitleDefaultApplied = false
    private var userPickedSubtitle = false
    private var progressJob: Job? = null
    private var released = false

    init {
        playbackState.setPlaying(true)
        player.setEventListener { event -> onPlayerEvent(event) }
    }

    fun attach(layout: VLCVideoLayout) {
        if (released || attachedLayout === layout) return
        if (attachedLayout != null) player.detachViews()
        player.attachViews(layout, null, true, false)
        attachedLayout = layout
        if (!started) {
            started = true
            viewModelScope.launch { startPlayback(resolveStartPosition(), hardware = true) }
        }
    }

    fun detach() {
        if (released) return
        if (attachedLayout != null) {
            player.detachViews()
            attachedLayout = null
        }
    }

    private suspend fun resolveStartPosition(): Long {
        if (startPositionMs > 0L) return startPositionMs
        val saved = runCatching { progressStore.get(tmdbId, mediaType, season, episode) }.getOrNull()
        return if (saved != null && saved.isResumable) saved.positionMs else 0L
    }

    private fun startPlayback(fromMs: Long, hardware: Boolean) {
        if (released) return
        val streamId = StreamUris.idOf(uri)
        if (streamId != null && streamProxyUrl == null) {
            // Stream movie: open the encrypted R2 stream through the localhost proxy, then play that.
            _uiState.update { it.copy(isLoading = true, error = null, finished = false) }
            viewModelScope.launch {
                val proxy = runCatching { streamSession.open(streamId) }
                    .onFailure { Log.w(TAG, "stream open failed: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
                if (proxy == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Streaming isn't available right now.") }
                    return@launch
                }
                if (released) { proxy.stop(); return@launch }
                streamProxy = proxy
                streamProxyUrl = withContext(Dispatchers.IO) { proxy.start() }
                startPlayback(fromMs, hardware)
            }
            return
        }
        val mediaUri = if (streamId != null) Uri.parse(streamProxyUrl) else toVlcUri(uri)
        if (mediaUri == null) {
            _uiState.update { it.copy(isLoading = false, error = "This file is not on the drive.") }
            return
        }
        subtitleDefaultApplied = false
        _uiState.update { it.copy(isLoading = true, error = null, finished = false) }
        val media = Media(libVlc, mediaUri)
        media.setHWDecoderEnabled(hardware, false)
        if (streamId != null) media.addOption(":network-caching=3000")
        if (fromMs > 0L) media.addOption(":start-time=${fromMs / 1000.0}")
        player.setMedia(media)
        media.release()
        player.play()
        startProgressLoop()
    }

    private fun onPlayerEvent(event: MediaPlayer.Event) {
        if (released) return
        when (event.type) {
            MediaPlayer.Event.Opening -> _uiState.update { it.copy(isLoading = true) }
            MediaPlayer.Event.Buffering ->
                _uiState.update { it.copy(isLoading = event.buffering < 100f && !it.isPlaying) }
            MediaPlayer.Event.Playing -> {
                _uiState.update { it.copy(isLoading = false, isPlaying = true, error = null) }
                refreshTracks()
            }
            MediaPlayer.Event.Paused -> {
                _uiState.update { it.copy(isPlaying = false) }
                saveProgressAsync()
            }
            MediaPlayer.Event.Stopped -> _uiState.update { it.copy(isPlaying = false) }
            MediaPlayer.Event.TimeChanged -> _uiState.update { it.copy(positionMs = event.timeChanged) }
            MediaPlayer.Event.LengthChanged -> _uiState.update { it.copy(durationMs = event.lengthChanged) }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> refreshTracks()
            MediaPlayer.Event.EndReached -> onEndReached()
            MediaPlayer.Event.EncounteredError -> onPlaybackError()
        }
    }

    private fun onEndReached() {
        val duration = _uiState.value.durationMs
        val proxy = streamProxy
        if (proxy != null && !StreamEnd.isRealEnd(proxy.failed, _uiState.value.positionMs, duration)) {
            // The stream was cut (network, expired link, bad chunk) — keep the resume point, show an error.
            saveProgressAsync()
            _uiState.update { it.copy(isPlaying = false, isLoading = false, error = "The stream was interrupted. Press Back and play again to resume.") }
            return
        }
        if (duration > 0L) saveProgressAsync(positionOverride = duration)
        val next = nextEpisode()
        if (next != null) {
            playEpisode(next.first, next.second, next.third)
        } else {
            _uiState.update { it.copy(isPlaying = false, finished = true) }
        }
    }

    private fun onPlaybackError() {
        Log.w(TAG, "VLC EncounteredError (hardware retry done=$softwareRetryDone) uri=$uri")
        if (!softwareRetryDone) {
            softwareRetryDone = true
            val resumeAt = _uiState.value.positionMs.takeIf { it > 0L } ?: startPositionMs.coerceAtLeast(0L)
            startPlayback(resumeAt, hardware = false)
            return
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                isPlaying = false,
                error = "This video couldn't be played. The file may be damaged or still downloading."
            )
        }
    }

    private fun refreshTracks() {
        val audio = player.audioTracks.orEmpty()
            .filter { it.id >= 0 }
            .map { VlcTrack(it.id, it.name ?: "Track ${it.id}") }
        val subs = player.spuTracks.orEmpty()
            .filter { it.id >= 0 }
            .map { VlcTrack(it.id, it.name ?: "Subtitle ${it.id}") }
        if (!subtitleDefaultApplied && !userPickedSubtitle && _uiState.value.isPlaying) {
            subtitleDefaultApplied = true
            if (!autoSubtitles) {
                // Subtitles turned off in Settings.
                if (player.spuTrack >= 0) player.setSpuTrack(-1)
            } else {
                // VLC grabs the first track in the language, which is often a
                // "Forced" track (foreign-language lines only). Prefer the full one.
                bestSubtitleTrack(subs)?.let { if (it != player.spuTrack) player.setSpuTrack(it) }
            }
        }
        _uiState.update {
            it.copy(
                audioTracks = audio,
                subtitleTracks = subs,
                selectedAudioId = player.audioTrack,
                selectedSubtitleId = player.spuTrack,
                hasNextEpisode = nextEpisode() != null
            )
        }
    }

    /**
     * The subtitle track to show by default: one in the preferred language,
     * full dialogue first (not Forced), plain before SDH/CC. Null if none match.
     */
    private fun bestSubtitleTrack(subs: List<VlcTrack>): Int? {
        val langs = languageList(prefs.subtitleLanguage).split(",") +
            (languageNames[prefs.subtitleLanguage.lowercase()] ?: emptyList())
        // Whole words only: "en" must not match "French".
        fun matches(name: String): Boolean {
            val words = name.lowercase().split(Regex("[^\\p{L}]+")).toSet()
            return langs.any { it.isNotBlank() && it in words }
        }
        val inLang = subs.filter { matches(it.name) }
        if (inLang.isEmpty()) return null
        return inLang.minByOrNull { t ->
            val n = t.name.lowercase()
            (if ("forced" in n) 10 else 0) + (if ("sdh" in n || "cc" in n.split(Regex("\\W+")) || "hearing" in n) 1 else 0)
        }?.id
    }

    fun togglePlayPause() {
        if (released || _uiState.value.error != null) return
        if (_uiState.value.finished) {
            restartFromBeginning()
            return
        }
        if (player.isPlaying) player.pause() else player.play()
    }

    /** App went to the background (Home button, screensaver): pause and free the drive. */
    fun onBackground() {
        if (released) return
        pause()
        saveProgressAsync()
        playbackState.setPlaying(false)
    }

    fun onForeground() {
        if (released) return
        playbackState.setPlaying(true)
    }

    fun play() {
        if (!released && !player.isPlaying && _uiState.value.error == null) player.play()
    }

    fun pause() {
        if (!released && player.isPlaying) player.pause()
    }

    /** Seeks to an absolute position (clamped). */
    fun seekTo(targetMs: Long) {
        if (released) return
        val duration = _uiState.value.durationMs
        val clamped = if (duration > 0L) targetMs.coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L)) else targetMs.coerceAtLeast(0L)
        player.setTime(clamped, false)
        _uiState.update { it.copy(positionMs = clamped) }
    }

    fun selectAudioTrack(id: Int) {
        if (released) return
        player.setAudioTrack(id)
        _uiState.update { it.copy(selectedAudioId = player.audioTrack) }
    }

    fun selectSubtitleTrack(id: Int) {
        if (released) return
        userPickedSubtitle = true
        player.setSpuTrack(id)
        _uiState.update { it.copy(selectedSubtitleId = player.spuTrack) }
    }

    fun playNextEpisode() {
        val next = nextEpisode() ?: return
        saveProgressAsync()
        playEpisode(next.first, next.second, next.third)
    }

    private fun restartFromBeginning() {
        startPositionMs = -1L
        softwareRetryDone = false
        startPlayback(0L, hardware = true)
    }

    private fun playEpisode(nextSeason: Int, nextEpisode: Int, nextUri: String) {
        season = nextSeason
        episode = nextEpisode
        uri = nextUri
        streamProxy?.stop()
        streamProxy = null
        streamProxyUrl = null
        startPositionMs = -1L
        softwareRetryDone = false
        _uiState.value = VlcPlayerUiState(title = showTitle, subtitle = episodeLabel(), isSeries = true)
        viewModelScope.launch { startPlayback(resolveStartPosition(), hardware = true) }
    }

    /** Next downloaded episode of the same show: e+1, else the next season's first. */
    private fun nextEpisode(): Triple<Int, Int, String>? {
        if (mediaType != MediaType.TV) return null
        val s = season ?: return null
        val e = episode ?: return null
        libraryBuilder.cachedEpisodePath(tmdbId, s, e + 1)?.let { return Triple(s, e + 1, it) }
        libraryBuilder.cachedEpisodePath(tmdbId, s + 1, 1)?.let { return Triple(s + 1, 1, it) }
        return null
    }

    private fun episodeLabel(): String? =
        if (mediaType == MediaType.TV && season != null && episode != null) "S$season:E$episode" else null

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                if (!released && player.isPlaying) saveProgress()
            }
        }
    }

    private fun saveProgressAsync(positionOverride: Long? = null) {
        val snapshot = progressSnapshot(positionOverride) ?: return
        appScope.launch { persist(snapshot) }
    }

    private suspend fun saveProgress() {
        val snapshot = progressSnapshot(null) ?: return
        withContext(Dispatchers.IO) { persist(snapshot) }
    }

    private data class Snapshot(val season: Int?, val episode: Int?, val positionMs: Long, val durationMs: Long)

    private fun progressSnapshot(positionOverride: Long?): Snapshot? {
        if (tmdbId <= 0) return null
        val state = _uiState.value
        val duration = state.durationMs.takeIf { it > 0L } ?: return null
        val position = positionOverride ?: (if (!released) player.time else state.positionMs).takeIf { it >= 0L } ?: state.positionMs
        if (position <= 0L) return null
        return Snapshot(season, episode, position, duration)
    }

    private suspend fun persist(s: Snapshot) {
        runCatching {
            progressStore.save(tmdbId, mediaType, s.season, s.episode, s.positionMs, s.durationMs, showTitle)
        }.onFailure { Log.w(TAG, "saving progress failed", it) }
    }

    override fun onCleared() {
        val finalSnapshot = progressSnapshot(null)
        released = true
        progressJob?.cancel()
        runCatching {
            player.setEventListener(null)
            player.stop()
            if (attachedLayout != null) player.detachViews()
            player.release()
            libVlc.release()
        }.onFailure { Log.w(TAG, "releasing VLC failed", it) }
        streamProxy?.stop() // zeroes the movie key and drops cached plaintext
        streamProxy = null
        attachedLayout = null
        playbackState.setPlaying(false)
        if (finalSnapshot != null) {
            appScope.launch { withContext(NonCancellable) { persist(finalSnapshot) } }
        }
        super.onCleared()
    }

    private fun toVlcUri(raw: String): Uri? {
        if (raw.isBlank()) return null
        val path = when {
            raw.startsWith("file://") -> Uri.parse(raw).path ?: raw.removePrefix("file://")
            raw.startsWith("/") -> raw
            else -> return Uri.parse(raw)
        }
        val file = File(path)
        return if (file.exists()) Uri.fromFile(file) else null
    }

    companion object {
        private const val TAG = "VlcPlayer"
        private const val PROGRESS_SAVE_INTERVAL_MS = 3_000L

        const val ARG_URI = "uri"
        const val ARG_TITLE = "title"
        const val ARG_TMDB_ID = "tmdbId"
        const val ARG_TYPE = "type"
        const val ARG_SEASON = "season"
        const val ARG_EPISODE = "episode"
        const val ARG_START_MS = "startMs"

        /** Survives the ViewModel so the final progress write isn't cancelled on exit. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** libVLC options from Settings → Subtitles / Language (see [PlayerPrefs]). */
        private fun vlcOptions(p: PlayerPrefsValues): ArrayList<String> = arrayListOf(
            "--audio-time-stretch",
            "--audio-language=${languageList(p.audioLanguage)}",
            "--sub-language=${if (p.subtitleLanguage.isBlank()) "none" else languageList(p.subtitleLanguage)}",
            "--no-sub-autodetect-file",
            "--sub-text-scale=${p.subtitleScalePercent}",
            // Netflix-style text: bold white with a soft dark outline and drop
            // shadow, no box, lifted a little off the bottom edge. (Styled ASS/SSA
            // tracks keep their own styling.)
            "--freetype-bold",
            "--freetype-color=${p.subtitleColor and 0xFFFFFF}",
            "--freetype-outline-thickness=2",
            "--freetype-outline-color=0",
            "--freetype-outline-opacity=200",
            "--freetype-shadow-color=0",
            "--freetype-shadow-opacity=170",
            "--freetype-shadow-distance=0.05",
            "--sub-margin=48",
            "--freetype-background-color=0",
            "--freetype-background-opacity=${if (p.subtitleBackground) 160 else 0}",
            // 5 s read-ahead from the USB drive: absorbs short stalls when the
            // disk head is also serving a torrent write.
            "--file-caching=5000"
        )

        /** Words VLC uses in track names, e.g. "Track 3 - [English]". */
        private val languageNames = mapOf(
            "eng" to listOf("english"), "fre" to listOf("french", "français"), "spa" to listOf("spanish", "español"),
            "msa" to listOf("malay"), "may" to listOf("malay"), "chi" to listOf("chinese"), "zho" to listOf("chinese"),
            "ind" to listOf("indonesian"), "jpn" to listOf("japanese"), "kor" to listOf("korean"), "tam" to listOf("tamil")
        )

        /** "eng" → "eng,en" so both 3- and 2-letter track tags match. */
        private fun languageList(iso639_2: String): String {
            val two = mapOf(
                "eng" to "en", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "spa" to "es",
                "ita" to "it", "por" to "pt", "dut" to "nl", "nld" to "nl", "jpn" to "ja", "kor" to "ko",
                "chi" to "zh", "zho" to "zh", "msa" to "ms", "may" to "ms", "ind" to "id", "hin" to "hi",
                "tam" to "ta", "ara" to "ar", "rus" to "ru", "tha" to "th", "vie" to "vi"
            )[iso639_2.lowercase()]
            return listOfNotNull(iso639_2.lowercase(), two).joinToString(",")
        }
    }
}
