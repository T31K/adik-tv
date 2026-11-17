package com.arflix.tv.ui.screens.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

private const val CONTROLS_HIDE_MS = 4_000L
private const val SEEK_COMMIT_DELAY_MS = 650L

/**
 * Full-screen libVLC player for TV remotes.
 *
 * OK = play/pause (and show controls) · Left/Right = seek -10/+10 s, accelerating while
 * held · Up = show controls · Down = show controls, then audio/subtitle menu ·
 * Back = close menu, hide controls, then exit. Media keys are handled too.
 */
@Composable
fun VlcPlayerScreen(
    onBack: () -> Unit,
    viewModel: VlcPlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)

    var controlsVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    val rootFocus = remember { FocusRequester() }

    fun poke() {
        controlsVisible = true
        interactionTick++
    }

    fun commitSeek() {
        pendingSeekMs?.let { viewModel.seekTo(it) }
        pendingSeekMs = null
    }

    fun stepSeek(forward: Boolean, repeatCount: Int) {
        val step = when {
            repeatCount < 5 -> 10_000L
            repeatCount < 15 -> 30_000L
            repeatCount < 30 -> 60_000L
            else -> 120_000L
        }
        val base = pendingSeekMs ?: state.positionMs
        val duration = state.durationMs
        val target = (if (forward) base + step else base - step).let {
            if (duration > 0L) it.coerceIn(0L, duration) else it.coerceAtLeast(0L)
        }
        pendingSeekMs = target
        poke()
    }

    // Keep the screen awake and free the drive while the app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // Auto-hide the controls while playing.
    LaunchedEffect(controlsVisible, interactionTick, state.isPlaying, menuOpen) {
        if (controlsVisible && state.isPlaying && !menuOpen) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }

    // Commit a D-pad seek shortly after the last press (covers remotes without key-up).
    LaunchedEffect(pendingSeekMs) {
        if (pendingSeekMs != null) {
            delay(SEEK_COMMIT_DELAY_MS)
            commitSeek()
        }
    }

    LaunchedEffect(menuOpen) {
        if (!menuOpen) runCatching { rootFocus.requestFocus() }
    }

    BackHandler {
        when {
            menuOpen -> menuOpen = false
            controlsVisible && state.error == null -> controlsVisible = false
            else -> currentOnBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (menuOpen) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                if (event.type == KeyEventType.KeyUp) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> { commitSeek(); true }
                        else -> false
                    }
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (native.repeatCount == 0) {
                            if (state.error != null) currentOnBack() else viewModel.togglePlayPause()
                        }
                        poke(); true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { stepSeek(false, native.repeatCount); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { stepSeek(true, native.repeatCount); true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { poke(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (controlsVisible && native.repeatCount == 0 && state.error == null) menuOpen = true
                        poke(); true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    AndroidKeyEvent.KEYCODE_SPACE -> { viewModel.togglePlayPause(); poke(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { viewModel.play(); poke(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { viewModel.pause(); poke(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { stepSeek(true, native.repeatCount + 5); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { stepSeek(false, native.repeatCount + 5); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { viewModel.playNextEpisode(); poke(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_STOP -> { currentOnBack(); true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> VLCVideoLayout(ctx).apply { keepScreenOn = true } },
            update = { layout -> viewModel.attach(layout) }
        )
        DisposableEffect(Unit) { onDispose { viewModel.detach() } }

        if (state.isLoading && state.error == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(56.dp),
                color = Color.White,
                strokeWidth = 4.dp
            )
        }

        state.error?.let { message ->
            ErrorPanel(message = message, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = (controlsVisible || pendingSeekMs != null || !state.isPlaying) && state.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            ControlsOverlay(state = state, pendingSeekMs = pendingSeekMs)
        }

        if (menuOpen) {
            TrackMenu(
                state = state,
                onSelectAudio = { viewModel.selectAudioTrack(it) },
                onSelectSubtitle = { viewModel.selectSubtitleTrack(it) },
                onClose = { menuOpen = false },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun ControlsOverlay(state: VlcPlayerUiState, pendingSeekMs: Long?) {
    val shownPosition = pendingSeekMs ?: state.positionMs
    val duration = state.durationMs
    Box(modifier = Modifier.fillMaxSize()) {
        // Top: title
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                .padding(horizontal = 48.dp, vertical = 32.dp)
        ) {
            Text(
                text = state.title,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            state.subtitle?.let {
                Text(text = it, color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp)
            }
        }

        // Bottom: progress + times
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(horizontal = 48.dp, vertical = 32.dp)
        ) {
            if (!state.isPlaying && !state.isLoading) {
                Text(
                    text = if (state.finished) "Finished · press OK to watch again" else "Paused",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            val fraction = if (duration > 0L) (shownPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(if (pendingSeekMs != null) Color(0xFFFFC107) else Color.White)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(shownPosition), color = Color.White, fontSize = 15.sp)
                Text(
                    text = "▼ Audio & subtitles" + if (state.hasNextEpisode) "   ⏭ Next episode" else "",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )
                Text(
                    text = if (duration > 0L) "-" + formatTime((duration - shownPosition).coerceAtLeast(0L)) else "--:--",
                    color = Color.White,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
private fun TrackMenu(
    state: VlcPlayerUiState,
    onSelectAudio: (Int) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onClose() }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(Color(0xF0141414))
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { MenuHeader("Audio") }
            if (state.audioTracks.isEmpty()) {
                item { MenuNote("No audio tracks") }
            }
            items(state.audioTracks, key = { "a${it.id}" }) { track ->
                val isFirst = track == state.audioTracks.first()
                TrackRow(
                    label = track.name,
                    selected = track.id == state.selectedAudioId,
                    onClick = { onSelectAudio(track.id) },
                    modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
            item { MenuHeader("Subtitles") }
            item {
                TrackRow(
                    label = "Off",
                    selected = state.selectedSubtitleId < 0,
                    onClick = { onSelectSubtitle(-1) },
                    modifier = if (state.audioTracks.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
            items(state.subtitleTracks, key = { "s${it.id}" }) { track ->
                TrackRow(
                    label = track.name,
                    selected = track.id == state.selectedSubtitleId,
                    onClick = { onSelectSubtitle(track.id) }
                )
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun MenuNote(text: String) {
    Text(text = text, color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp)
}

@Composable
private fun TrackRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .border(1.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (selected) "✓" else "",
            color = if (focused) Color.Black else Color.White,
            fontSize = 16.sp,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = label,
            color = if (focused) Color.Black else Color.White,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ErrorPanel(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE6202020))
            .padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Can't play this video", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(message, color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        Text("Press Back to return", color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
