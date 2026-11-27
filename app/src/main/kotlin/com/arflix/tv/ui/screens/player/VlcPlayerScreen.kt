package com.arflix.tv.ui.screens.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
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
    var focus by remember { mutableStateOf(ChromeFocus()) }
    val buttons = PlayerChrome.buttons(state.isSeries)
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
            focus = ChromeFocus()
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
                // Diagnostics for the "can't pause" report (warn level survives release builds).
                android.util.Log.w("VlcKeys", "key=${native.keyCode} repeat=${native.repeatCount} playing=${state.isPlaying} loading=${state.isLoading} controls=$controlsVisible focus=$focus")
                // D-pad goes through the Netflix-layout focus model (PlayerChrome). While the controls
                // are hidden, OK and Left/Right still act right away; Up/Down just bring the controls up.
                val chromeKey = when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> ChromeKey.OK
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> ChromeKey.LEFT
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> ChromeKey.RIGHT
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> ChromeKey.UP
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> ChromeKey.DOWN
                    else -> null
                }
                if (chromeKey != null) {
                    if (state.error != null) {
                        if (chromeKey == ChromeKey.OK && native.repeatCount == 0) currentOnBack()
                        return@onPreviewKeyEvent true
                    }
                    val wasHidden = !controlsVisible
                    if (wasHidden && (chromeKey == ChromeKey.UP || chromeKey == ChromeKey.DOWN)) {
                        focus = ChromeFocus(); poke(); return@onPreviewKeyEvent true
                    }
                    val from = if (wasHidden) ChromeFocus() else focus
                    val (next, action) = PlayerChrome.onKey(from, chromeKey, buttons)
                    focus = next
                    when (action) {
                        ChromeAction.TOGGLE_PLAY -> if (native.repeatCount == 0) viewModel.togglePlayPause()
                        ChromeAction.SEEK_BACK -> stepSeek(false, native.repeatCount)
                        ChromeAction.SEEK_FORWARD -> stepSeek(true, native.repeatCount)
                        ChromeAction.OPEN_TRACKS -> if (native.repeatCount == 0) menuOpen = true
                        ChromeAction.OPEN_EPISODES -> if (native.repeatCount == 0) currentOnBack()
                        ChromeAction.NONE -> Unit
                    }
                    poke()
                    return@onPreviewKeyEvent true
                }
                when (native.keyCode) {
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
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    keepScreenOn = true
                    // The native video view must never take D-pad focus: if it does,
                    // OK/Play presses go to it instead of our controls (the "can't
                    // pause" report). All keys belong to the Compose root.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
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
            ControlsOverlay(state = state, pendingSeekMs = pendingSeekMs, focus = focus, buttons = buttons)
        }

        // Audio & Subtitles panel slides in from the right edge (and back out on close).
        AnimatedVisibility(
            visible = menuOpen,
            enter = slideInHorizontally(animationSpec = tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(animationSpec = tween(220)) { it } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            TrackMenu(
                state = state,
                onSelectAudio = { viewModel.selectAudioTrack(it) },
                onSelectSubtitle = { viewModel.selectSubtitleTrack(it) },
                onClose = { menuOpen = false }
            )
        }
    }
}

/** Red for the played part of the bar and the knob. */
private val PlayedRed = Color(0xFFE53935)

/**
 * Netflix-layout controls: back arrow + centred title at the top; a large play/pause in the
 * middle with 10-second skip icons on either side; a thin bar with a red knob and the time
 * remaining; and labeled buttons underneath. Focus comes from [PlayerChrome] (the root
 * handles every key), so these composables only draw.
 */
@Composable
private fun ControlsOverlay(
    state: VlcPlayerUiState,
    pendingSeekMs: Long?,
    focus: ChromeFocus,
    buttons: List<ChromeButton>
) {
    val shownPosition = pendingSeekMs ?: state.positionMs
    val duration = state.durationMs
    val centreFocused = focus.zone == ChromeZone.CENTER
    Box(modifier = Modifier.fillMaxSize()) {
        // Top: back arrow, centred title. Never a filename or URL — the title comes from the catalog.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .padding(horizontal = 40.dp, vertical = 28.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.align(Alignment.CenterStart).size(30.dp)
            )
            Text(
                text = listOfNotNull(state.title.takeIf { it.isNotBlank() }, state.subtitle).joinToString(" · "),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 80.dp)
            )
        }

        // Centre: ⟲10   ▶/❚❚   10⟳ (hidden while the spinner shows)
        if (!state.isLoading) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(150.dp)
            ) {
                val sideTint = Color.White.copy(alpha = if (centreFocused) 0.9f else 0.5f)
                Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds", tint = sideTint, modifier = Modifier.size(64.dp))
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(if (centreFocused) Color.White.copy(alpha = 0.18f) else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = Color.White.copy(alpha = if (centreFocused) 1f else 0.6f),
                        modifier = Modifier.size(84.dp)
                    )
                }
                Icon(Icons.Default.Forward10, contentDescription = "Forward 10 seconds", tint = sideTint, modifier = Modifier.size(64.dp))
            }
        }

        // Bottom: seek bar + time remaining, then the labeled buttons.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 24.dp)
        ) {
            if (state.finished) {
                Text(
                    text = "Finished · press OK to watch again",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            val fraction = if (duration > 0L) (shownPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(modifier = Modifier.weight(1f).height(40.dp)) {
                    val knob = 18.dp
                    val knobX = (maxWidth - knob) * fraction
                    if (pendingSeekMs != null) {
                        Text(
                            text = formatTime(shownPosition),
                            color = Color.Black,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .offset(x = (knobX - 24.dp).coerceIn(0.dp, maxWidth - 64.dp), y = (-22).dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.3f))
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(PlayedRed))
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = knobX)
                            .size(knob)
                            .clip(CircleShape)
                            .background(PlayedRed)
                    )
                }
                Spacer(Modifier.width(20.dp))
                Text(
                    text = if (duration > 0L) formatTime((duration - shownPosition).coerceAtLeast(0L)) else "--:--",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                buttons.forEachIndexed { i, b ->
                    LabeledButton(
                        icon = if (b == ChromeButton.EPISODES) Icons.AutoMirrored.Filled.ViewList else Icons.Default.Subtitles,
                        label = if (b == ChromeButton.EPISODES) "Episodes" else "Audio & Subtitles",
                        focused = focus.zone == ChromeZone.BOTTOM && focus.button == i
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledButton(icon: ImageVector, label: String, focused: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = if (focused) Color.Black else Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
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
                    label = track.label,
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
                    label = track.label,
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
